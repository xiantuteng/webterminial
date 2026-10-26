package com.jingzhou.webterminal.terminal;

import com.jingzhou.webterminal.auth.SessionSecret;
import com.jingzhou.webterminal.auth.WtSession;
import com.jingzhou.webterminal.config.OriginChecks;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Map;

public class TerminalHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest)) {
            return false;
        }
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        if (!OriginChecks.allowed(servletRequest)) {
            return false;
        }
        HttpSession session = servletRequest.getSession(false);
        if (session == null || !(session.getAttribute(WtSession.USER) instanceof String)) {
            return false;
        }
        attributes.put(WtSession.USER, session.getAttribute(WtSession.USER));
        attributes.put(WtSession.HOME, session.getAttribute(WtSession.HOME));
        attributes.put(WtSession.SHELL, session.getAttribute(WtSession.SHELL));
        attributes.put("httpSession", session);
        Object secret = session.getAttribute(WtSession.SECRET);
        if (secret instanceof SessionSecret) {
            attributes.put(WtSession.SECRET, ((SessionSecret) secret).copy());
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

}
