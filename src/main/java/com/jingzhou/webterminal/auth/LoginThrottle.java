package com.jingzhou.webterminal.auth;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@Component
public class LoginThrottle {

    static final int MAX_FAILS = 8;
    static final long WINDOW_MS = 10L * 60L * 1000L;

    private final Map<String, Slot> slots = new HashMap<String, Slot>();

    public synchronized boolean tooMany(String key) {
        purge(System.currentTimeMillis());
        Slot slot = slots.get(key);
        return slot != null && slot.fails >= MAX_FAILS;
    }

    public synchronized void fail(String key) {
        long now = System.currentTimeMillis();
        purge(now);
        Slot slot = slots.get(key);
        if (slot == null) {
            slot = new Slot();
            slot.windowStart = now;
            slots.put(key, slot);
        }
        slot.fails++;
    }

    public synchronized void success(String key) {
        slots.remove(key);
    }

    private void purge(long now) {
        Iterator<Map.Entry<String, Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().windowStart > WINDOW_MS) {
                it.remove();
            }
        }
    }

    private static final class Slot {
        int fails;
        long windowStart;
    }
}
