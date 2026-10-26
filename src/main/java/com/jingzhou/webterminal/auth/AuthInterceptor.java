package com.jingzhou.webterminal.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper mapper;

    public AuthInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(WtSession.USER) != null) {
            return true;
        }
        response.setStatus(401);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("message", "未登录");
        mapper.writeValue(response.getWriter(), body);
        return false;
    }
}
