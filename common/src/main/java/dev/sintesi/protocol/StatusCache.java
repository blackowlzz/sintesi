package dev.sintesi.protocol;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class StatusCache {

    public record Entry(byte[] frame, long capturedAt) {
    }

    public static final int MAX_FRAME = 1 << 18;
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_HOST = 255;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public Entry get(int protocol, String host) {
        return entries.get(key(protocol, host));
    }

    public void store(int protocol, String host, byte[] frame, long now) {
        String key = key(protocol, host);
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) {
            return;
        }
        entries.put(key, new Entry(frame, now));
    }

    public int size() {
        return entries.size();
    }

    public static int completeResponseLength(byte[] data, int length) {
        int[] position = {0};
        int frameLength = readVarInt(data, length, position);
        if (frameLength < 0) {
            return frameLength == -1 ? 0 : -1;
        }
        if (frameLength < 3 || frameLength > MAX_FRAME) {
            return -1;
        }
        int total = position[0] + frameLength;
        if (length < total) {
            int id = position[0] < length ? data[position[0]] : 0;
            return id == 0 ? 0 : -1;
        }
        if (data[position[0]++] != 0) {
            return -1;
        }
        int stringLength = readVarInt(data, total, position);
        if (stringLength < 2 || position[0] + stringLength != total || data[position[0]] != '{') {
            return -1;
        }
        return total;
    }

    public static byte[] pong(byte[] payload) {
        byte[] frame = new byte[2 + payload.length];
        frame[0] = (byte) (1 + payload.length);
        frame[1] = 0x01;
        System.arraycopy(payload, 0, frame, 2, payload.length);
        return frame;
    }

    public static byte[] copy(byte[] data, int length) {
        return Arrays.copyOf(data, length);
    }

    private static String key(int protocol, String host) {
        String clean = Handshake.addressOf(host);
        if (clean.length() > MAX_HOST) {
            clean = clean.substring(0, MAX_HOST);
        }
        return protocol + "|" + clean.toLowerCase(Locale.ROOT);
    }

    private static int readVarInt(byte[] data, int limit, int[] position) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (position[0] >= limit) {
                return -1;
            }
            byte current = data[position[0]++];
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        return -2;
    }
}
