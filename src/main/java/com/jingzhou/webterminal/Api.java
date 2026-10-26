package com.jingzhou.webterminal;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Api {

    private Api() {
    }

    public static Map<String, Object> ok() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", true);
        return body;
    }

    public static Map<String, Object> fail(String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ok", false);
        body.put("message", message);
        return body;
    }
}
