package com.jingzhou.webterminal.config;

import javax.servlet.http.HttpServletRequest;

public final class OriginChecks {

    private OriginChecks() {
    }

    public static boolean allowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.trim().isEmpty()) {
            return true;
        }
        String host = request.getHeader("Host");
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        return origin.equals(request.getScheme() + "://" + host);
    }
}
