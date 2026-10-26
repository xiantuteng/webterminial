package com.jingzhou.webterminal.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingzhou.webterminal.auth.Account;
import com.jingzhou.webterminal.auth.WtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TerminalHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalHandler.class);

    private final ShellLauncher launcher;
    private final ObjectMapper mapper;
    private final Set<TerminalBridge> bridges = ConcurrentHashMap.newKeySet();

    public TerminalHandler(ShellLauncher launcher, ObjectMapper mapper) {
        this.launcher = launcher;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String user = stringAttr(session, WtSession.USER);
        String home = stringAttr(session, WtSession.HOME);
        String shell = stringAttr(session, WtSession.SHELL);
        if (user == null || home == null || shell == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        char[] secret = null;
        Object boxed = session.getAttributes().get(WtSession.SECRET);
        if (boxed instanceof char[]) {
            secret = (char[]) boxed;
        }
        TerminalBridge bridge = null;
        try {
            bridge = new TerminalBridge(session, launcher, mapper, new Account(user, home, shell), secret);
            session.getAttributes().put("bridge", bridge);
            bridges.add(bridge);
            bridge.start();
        } catch (IOException ex) {
            log.warn("打开终端失败 user={}", user);
            if (bridge != null) {
                bridge.close();
                bridges.remove(bridge);
            } else if (secret != null) {
                Arrays.fill(secret, '\0');
            }
            sendAndClose(session, ex.getMessage() == null ? "无法打开终端" : ex.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!touch(session)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        TerminalBridge bridge = (TerminalBridge) session.getAttributes().get("bridge");
        if (bridge == null) {
            return;
        }
        String payload = message.getPayload();
        if (payload == null || payload.length() > 512 * 1024) {
            return;
        }
        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (Exception ex) {
            return;
        }
        String type = node.path("type").asText();
        if ("ping".equals(type)) {
            Map<String, Object> pong = new LinkedHashMap<String, Object>();
            pong.put("type", "pong");
            session.sendMessage(new TextMessage(mapper.writeValueAsString(pong)));
            return;
        }
        if ("resize".equals(type)) {
            int cols = node.path("cols").asInt(80);
            int rows = node.path("rows").asInt(24);
            if (cols < 2 || cols > 500 || rows < 1 || rows > 200) {
                return;
            }
            bridge.resize(cols, rows);
            return;
        }
        if ("data".equals(type)) {
            String data = node.path("data").asText("");
            if (data.length() > 256 * 1024) {
                return;
            }
            bridge.input(data);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        TerminalBridge bridge = (TerminalBridge) session.getAttributes().get("bridge");
        if (bridge != null) {
            bridge.close();
            bridges.remove(bridge);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (TerminalBridge bridge : bridges) {
            bridge.close();
        }
        bridges.clear();
    }

    private boolean touch(WebSocketSession session) {
        Object httpSession = session.getAttributes().get("httpSession");
        if (!(httpSession instanceof HttpSession)) {
            return false;
        }
        try {
            return ((HttpSession) httpSession).getAttribute(WtSession.USER) != null;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private void sendAndClose(WebSocketSession session, String message) {
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("type", "error");
            body.put("message", message);
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(body)));
                session.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static String stringAttr(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value instanceof String ? (String) value : null;
    }
}
