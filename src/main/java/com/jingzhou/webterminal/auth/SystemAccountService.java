package com.jingzhou.webterminal.auth;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class SystemAccountService {

    private static final Logger log = LoggerFactory.getLogger(SystemAccountService.class);
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9._-]{0,31}$");

    public Account lookup(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            return null;
        }
        try {
            if (isMac()) {
                return lookupMac(username);
            }
            if (isLinux()) {
                return lookupLinux(username);
            }
        } catch (Exception ex) {
            log.warn("读取系统账号失败 user={}", username);
        }
        return null;
    }

    public boolean authenticate(Account account, String password) {
        if (account == null || password == null || password.isEmpty() || password.length() > 1024) {
            return false;
        }
        try {
            if (isMac()) {
                return authMac(account.getUsername(), password);
            }
            if (isLinux()) {
                return authSu(account.getUsername(), password);
            }
        } catch (Exception ex) {
            log.warn("系统账号校验失败 user={}", account.getUsername());
        }
        return false;
    }

    public static boolean sameProcessUser(String username) {
        return username != null && username.equals(System.getProperty("user.name", ""));
    }

    static Account parsePasswdLine(String username, String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        String[] parts = trimmed.split(":");
        if (parts.length < 7 || !username.equals(parts[0])) {
            return null;
        }
        String home = parts[5];
        String shell = parts[6].trim();
        if (!isSafeAbsolute(home) || !isSafeAbsolute(shell)) {
            return null;
        }
        return new Account(username, home, shell);
    }

    private Account lookupMac(String username) throws Exception {
        String output = command(new String[]{"/usr/bin/dscacheutil", "-q", "user", "-a", "name", username}, 5);
        String name = null;
        String home = null;
        String shell = null;
        String[] lines = output.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            if ("name".equals(key)) {
                if (name != null && !username.equals(name)) {
                    break;
                }
                name = value;
            } else if ("dir".equals(key) && home == null) {
                home = value;
            } else if ("shell".equals(key) && shell == null) {
                shell = value;
            }
        }
        if (!username.equals(name) || !isSafeAbsolute(home)) {
            return null;
        }
        if (!isSafeAbsolute(shell)) {
            shell = "/bin/sh";
        }
        return new Account(username, home, shell);
    }

    private Account lookupLinux(String username) throws Exception {
        File getent = new File("/usr/bin/getent");
        if (getent.isFile()) {
            String output = command(new String[]{getent.getPath(), "passwd", username}, 5);
            String[] lines = output.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                Account account = parsePasswdLine(username, lines[i]);
                if (account != null) {
                    return account;
                }
            }
            return null;
        }
        List<String> lines = Files.readAllLines(Paths.get("/etc/passwd"), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            Account account = parsePasswdLine(username, lines.get(i));
            if (account != null) {
                return account;
            }
        }
        return null;
    }

    private boolean authMac(String username, String password) throws Exception {
        Process process = new ProcessBuilder("/usr/bin/dscl", ".", "-authonly", username, password)
                .redirectErrorStream(true)
                .start();
        drain(process.getInputStream());
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private boolean authSu(String username, String password) throws Exception {
        String su = new File("/usr/bin/su").isFile() ? "/usr/bin/su" : "/bin/su";
        if (!new File(su).isFile()) {
            return false;
        }
        Map<String, String> env = new HashMap<String, String>();
        env.put("TERM", "xterm-256color");
        env.put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        PtyProcess process = new PtyProcessBuilder()
                .setCommand(new String[]{su, username, "-c", "true"})
                .setEnvironment(env)
                .setRedirectErrorStream(true)
                .start();
        final ByteArrayOutputStream seen = new ByteArrayOutputStream();
        final InputStream input = process.getInputStream();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[256];
                try {
                    int n;
                    while ((n = input.read(buf)) != -1) {
                        synchronized (seen) {
                            if (seen.size() < 8192) {
                                seen.write(buf, 0, n);
                            }
                            seen.notifyAll();
                        }
                    }
                } catch (IOException ignored) {
                    // su 结束时读取会中断。
                }
            }
        }, "wt-su-auth");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + 8000L;
        boolean sent = false;
        while (System.currentTimeMillis() < deadline) {
            String text;
            synchronized (seen) {
                text = new String(seen.toByteArray(), StandardCharsets.UTF_8);
            }
            if (!sent && (text.contains("Password:") || text.contains("password:"))) {
                process.getOutputStream().write(password.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().write('\n');
                process.getOutputStream().flush();
                sent = true;
            }
            if (!process.isRunning()) {
                break;
            }
            Thread.sleep(30L);
        }
        if (process.isRunning() && !process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private static String command(String[] command, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Thread reader = drain(process.getInputStream(), sink, 16384);
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            reader.interrupt();
            return "";
        }
        reader.join(1000L);
        return new String(sink.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void drain(InputStream input) {
        drain(input, new ByteArrayOutputStream(), 4096);
    }

    private static Thread drain(final InputStream input, final ByteArrayOutputStream sink, final int max) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[512];
                try {
                    int n;
                    while ((n = input.read(buf)) != -1) {
                        if (sink.size() < max) {
                            sink.write(buf, 0, Math.min(n, max - sink.size()));
                        }
                    }
                } catch (IOException ignored) {
                    // 进程结束时管道关闭是正常的。
                }
            }
        }, "wt-auth-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static boolean isSafeAbsolute(String path) {
        return path != null
                && path.startsWith("/")
                && path.indexOf('\0') < 0
                && path.indexOf('\n') < 0
                && path.indexOf('\r') < 0;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
