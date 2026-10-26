package com.jingzhou.webterminal.terminal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class Utf8Decoder {

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private byte[] pending = new byte[0];

    String decode(byte[] data, int length) {
        byte[] all = new byte[pending.length + length];
        System.arraycopy(pending, 0, all, 0, pending.length);
        System.arraycopy(data, 0, all, pending.length, length);
        int complete = completeLength(all);
        CharBuffer output = CharBuffer.allocate(complete + 2);
        decoder.decode(ByteBuffer.wrap(all, 0, complete), output, false);
        int leftover = all.length - complete;
        pending = new byte[leftover];
        if (leftover > 0) {
            System.arraycopy(all, complete, pending, 0, leftover);
        }
        output.flip();
        return output.toString();
    }

    private static int completeLength(byte[] bytes) {
        int index = bytes.length;
        int continuation = 0;
        while (index > 0 && continuation < 4 && (bytes[index - 1] & 0xC0) == 0x80) {
            continuation++;
            index--;
        }
        if (index == 0) {
            return bytes.length;
        }
        int lead = bytes[index - 1] & 0xFF;
        int need;
        if ((lead & 0x80) == 0) {
            return bytes.length;
        } else if ((lead & 0xE0) == 0xC0) {
            need = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            need = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            need = 3;
        } else {
            return bytes.length;
        }
        if (continuation < need) {
            return index - 1;
        }
        return bytes.length;
    }
}
