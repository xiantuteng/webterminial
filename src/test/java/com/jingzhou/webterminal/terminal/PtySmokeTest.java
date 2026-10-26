package com.jingzhou.webterminal.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PtySmokeTest {

    @Test
    void echoRunsInPty() throws Exception {
        PtyProcess process = new PtyProcessBuilder()
                .setCommand(new String[]{"/bin/echo", "pty-ok"})
                .setRedirectErrorStream(true)
                .start();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InputStream input = process.getInputStream();
        byte[] buf = new byte[256];
        int n;
        while ((n = input.read(buf)) != -1) {
            sink.write(buf, 0, n);
        }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        String text = new String(sink.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(text.contains("pty-ok"), text);
    }
}
