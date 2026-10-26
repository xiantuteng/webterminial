package com.jingzhou.webterminal.config;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Controller
public class NginxErrorController implements ErrorController {

    @RequestMapping("/error")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int code = 500;
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status instanceof Integer) {
            code = ((Integer) status).intValue();
        }
        String uri = String.valueOf(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        response.setStatus(code);
        response.setCharacterEncoding("UTF-8");
        if (uri.contains("/api/") || uri.contains("/ws/")) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"ok\":false,\"message\":\"请求无法完成\"}");
            return;
        }
        response.setContentType("text/html;charset=UTF-8");
        String reason = reason(code);
        response.getWriter().write("<!DOCTYPE html><html><head><title>"
                + code + " " + reason + "</title></head><body><center><h1>"
                + code + " " + reason + "</h1></center><hr><center>nginx</center></body></html>");
    }

    private static String reason(int code) {
        if (code == 400) {
            return "Bad Request";
        }
        if (code == 401) {
            return "Unauthorized";
        }
        if (code == 403) {
            return "Forbidden";
        }
        if (code == 404) {
            return "Not Found";
        }
        if (code == 413) {
            return "Request Entity Too Large";
        }
        return "Error";
    }
}
