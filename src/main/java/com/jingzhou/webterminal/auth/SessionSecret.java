package com.jingzhou.webterminal.auth;

import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import java.util.Arrays;

/**
 * 仅在需要 su 到其他系统账号时暂存在内存会话里，退出或会话失效时清掉。
 */
public final class SessionSecret implements HttpSessionBindingListener {

    private final char[] value;

    public SessionSecret(char[] value) {
        this.value = Arrays.copyOf(value, value.length);
    }

    public char[] copy() {
        return Arrays.copyOf(value, value.length);
    }

    public void clear() {
        Arrays.fill(value, '\0');
    }

    @Override
    public void valueBound(HttpSessionBindingEvent event) {
    }

    @Override
    public void valueUnbound(HttpSessionBindingEvent event) {
        clear();
    }
}
