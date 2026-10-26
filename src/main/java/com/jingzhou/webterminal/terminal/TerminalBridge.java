package com.jingzhou.webterminal.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingzhou.webterminal.auth.Account;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class TerminalBridge {

    private static final byte[] POISON = new byte[0];

    private final WebSocketSession socket;
    private final ObjectMapper mapper;
    private final PtyProcess process;
    private final boolean switchUser;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final Object sendLock = new Object();
    private final StringBuilder pendingInput = new StringBuilder();
    private final ArrayBlockingQueue<byte[]> chunks = new ArrayBlockingQueue<byte[]>(256);
    private volatile boolean proxying;
    private volatile Thread producer;
    private volatile Thread consumer;
    private char[] password;

    TerminalBridge(WebSocketSession socket, ShellLauncher launcher, ObjectMapper mapper,
                   Account account, char[] password) throws IOException {
        this.socket = socket;
        this.mapper = mapper;
        this.switchUser = launcher.needsSwitchUser(account);
        this.password = password == null ? null : Arrays.copyOf(password, password.length);
        if (password != null) {
            Arrays.fill(password, '\0');
        }
        if (this.switchUser && this.password == null) {
            throw new IOException("请重新登录后再打开终端");
        }
        this.process = launcher.open(account, 80, 24);
    }

    void start() {
        producer = new Thread(new Runnable() {
            @Override
            public void run() {
                readProcess();
            }
        }, "wt-pty-read");
        producer.setDaemon(true);
        consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                consume();
            }
        }, "wt-pty");
        consumer.setDaemon(true);
        producer.start();
        consumer.start();
    }

    void input(String data) throws IOException {
        synchronized (pendingInput) {
            if (proxying) {
                writeRaw(data);
            } else if (pendingInput.length() < 64 * 1024) {
                pendingInput.append(data);
            }
        }
    }

    void resize(int columns, int rows) {
        try {
            process.setWinSize(new WinSize(columns, rows));
        } catch (Exception ignored) {
            // 会话结束时调整窗口会失败，忽略即可。
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        wipe();
        try {
            process.destroy();
        } catch (Exception ignored) {
        }
        if (producer != null) {
            producer.interrupt();
        }
        if (consumer != null && consumer != Thread.currentThread()) {
            consumer.interrupt();
        }
        chunks.offer(POISON);
    }

    private void consume() {
        Utf8Decoder decoder = new Utf8Decoder();
        try {
            if (switchUser && !bootstrap(decoder)) {
                sendError("系统账号认证失败，无法打开终端");
                return;
            }
            enableProxy();
            while (!closed.get()) {
                byte[] chunk;
                try {
                    chunk = chunks.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (chunk == null) {
                    if (!process.isRunning() && chunks.isEmpty()) {
                        break;
                    }
                    continue;
                }
                if (chunk == POISON || chunk.length == 0) {
                    break;
                }
                String text = decoder.decode(chunk, chunk.length);
                if (!text.isEmpty()) {
                    sendData(text);
                }
            }
            int code = -1;
            try {
                if (process.isRunning()) {
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                code = process.exitValue();
            } catch (Exception ignored) {
                code = -1;
            }
            Map<String, Object> exit = new LinkedHashMap<String, Object>();
            exit.put("type", "exit");
            exit.put("code", Integer.valueOf(code));
            send(exit);
        } catch (Exception ex) {
            if (!closed.get()) {
                sendError("终端已断开");
            }
        } finally {
            close();
            closeSocket();
        }
    }

    private boolean bootstrap(Utf8Decoder decoder) throws Exception {
        StringBuilder buffered = new StringBuilder();
        long deadline = System.currentTimeMillis() + 5000L;
        boolean prompted = false;
        while (System.currentTimeMillis() < deadline && !closed.get()) {
            String piece = pollText(decoder, 200);
            if (piece == null) {
                if (!process.isRunning()) {
                    break;
                }
                continue;
            }
            buffered.append(piece);
            if (buffered.indexOf("Password:") >= 0 || buffered.indexOf("password:") >= 0) {
                prompted = true;
                break;
            }
        }
        if (prompted) {
            writePassword();
            long end = System.currentTimeMillis() + 1500L;
            long lastData = 0L;
            while (System.currentTimeMillis() < end && !closed.get()) {
                if (failed(buffered)) {
                    return false;
                }
                String piece = pollText(decoder, 80);
                if (piece == null) {
                    if (lastData > 0L && System.currentTimeMillis() - lastData > 120L) {
                        break;
                    }
                    if (!process.isRunning()) {
                        return process.exitValue() == 0 && !failed(buffered);
                    }
                    continue;
                }
                buffered.append(piece);
                lastData = System.currentTimeMillis();
                if (failed(buffered)) {
                    return false;
                }
            }
        }
        String forward = present(buffered.toString());
        wipe();
        if (!forward.isEmpty()) {
            sendData(forward);
        }
        return process.isRunning() || process.exitValue() == 0;
    }

    private String pollText(Utf8Decoder decoder, long timeoutMs) throws InterruptedException {
        byte[] chunk = chunks.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (chunk == null || chunk == POISON || chunk.length == 0) {
            if (chunk == POISON) {
                chunks.offer(POISON);
            }
            return null;
        }
        return decoder.decode(chunk, chunk.length);
    }

    private void enableProxy() throws IOException {
        synchronized (pendingInput) {
            proxying = true;
            if (pendingInput.length() > 0) {
                writeRaw(pendingInput.toString());
                pendingInput.setLength(0);
            }
        }
    }

    private void writePassword() throws IOException {
        char[] copy;
        synchronized (writeLock) {
            if (password == null) {
                return;
            }
            copy = Arrays.copyOf(password, password.length);
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(copy));
        try {
            synchronized (writeLock) {
                if (encoded.hasArray()) {
                    process.getOutputStream().write(encoded.array(), encoded.arrayOffset() + encoded.position(), encoded.remaining());
                } else {
                    byte[] bytes = new byte[encoded.remaining()];
                    encoded.get(bytes);
                    try {
                        process.getOutputStream().write(bytes);
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                }
                process.getOutputStream().write('\n');
                process.getOutputStream().flush();
            }
        } finally {
            Arrays.fill(copy, '\0');
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
        }
    }

    private String present(String raw) {
        String text = raw;
        char[] copy = null;
        synchronized (writeLock) {
            if (password != null && password.length > 0) {
                copy = Arrays.copyOf(password, password.length);
            }
        }
        if (copy != null) {
            try {
                text = text.replace(new String(copy), "");
            } finally {
                Arrays.fill(copy, '\0');
            }
        }
        text = text.replace("Password:", "").replace("password:", "");
        return text;
    }

    private static boolean failed(StringBuilder buffered) {
        String text = buffered.toString();
        return text.contains("Authentication failure") || text.contains("su: Sorry");
    }

    private void readProcess() {
        InputStream input = process.getInputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while (!closed.get() && (n = input.read(buf)) != -1) {
                if (n == 0) {
                    continue;
                }
                chunks.put(Arrays.copyOf(buf, n));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 进程关闭后读取结束。
        } finally {
            chunks.offer(POISON);
        }
    }

    private void writeRaw(String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            OutputStream output = process.getOutputStream();
            output.write(bytes);
            output.flush();
        }
    }

    private void sendData(String data) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("type", "data");
        body.put("data", data);
        send(body);
    }

    private void sendError(String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("type", "error");
        body.put("message", message);
        send(body);
    }

    private void send(Map<String, Object> body) {
        synchronized (sendLock) {
            if (!socket.isOpen()) {
                return;
            }
            try {
                socket.sendMessage(new TextMessage(mapper.writeValueAsString(body)));
            } catch (Exception ex) {
                close();
            }
        }
    }

    private void closeSocket() {
        try {
            if (socket.isOpen()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void wipe() {
        synchronized (writeLock) {
            if (password != null) {
                Arrays.fill(password, '\0');
                password = null;
            }
        }
    }
}
