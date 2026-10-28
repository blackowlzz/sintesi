package dev.sintesi.net;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class Blocklist {

    public enum Reason {
        MANUAL,
        FAILURES,
        FLOOD
    }

    public record Entry(long expiry, Reason reason, String code, String address) {
    }

    private static final int MAX_ENTRIES = 1 << 20;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final String CODE_PREFIX = "SINTESI-";
    private static final Reason[] REASONS = Reason.values();

    private final LongSupplier clock;
    private final ConcurrentHashMap<Long, Long> entries = new ConcurrentHashMap<>();
    private final long codeSalt = new SecureRandom().nextLong();

    public Blocklist(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean isBlocked(long key) {
        if (entries.isEmpty()) {
            return false;
        }
        Long packed = entries.get(key);
        return packed != null && expiryOf(packed) > clock.getAsLong();
    }

    public Entry entry(long key) {
        if (entries.isEmpty()) {
            return null;
        }
        Long packed = entries.get(key);
        return packed != null && expiryOf(packed) > clock.getAsLong() ? view(key, packed) : null;
    }

    public void block(long key, long durationMillis) {
        block(key, durationMillis, Reason.MANUAL);
    }

    public Entry block(long key, long durationMillis, Reason reason) {
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) {
            return null;
        }
        long packed = pack(clock.getAsLong() + durationMillis, reason);
        entries.put(key, packed);
        return view(key, packed);
    }

    public Entry blockNew(long key, long durationMillis, Reason reason) {
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) {
            return null;
        }
        long now = clock.getAsLong();
        boolean[] created = {false};
        Long packed = entries.compute(key, (ignored, current) -> {
            if (current != null && expiryOf(current) > now) {
                return current;
            }
            created[0] = true;
            return pack(now + durationMillis, reason);
        });
        return created[0] ? view(key, packed) : null;
    }

    public Long keyOfCode(String code) {
        String normalized = normalizeCode(code);
        if (normalized == null) {
            return null;
        }
        long now = clock.getAsLong();
        for (Map.Entry<Long, Long> item : entries.entrySet()) {
            if (expiryOf(item.getValue()) > now && codeOf(item.getKey()).equals(normalized)) {
                return item.getKey();
            }
        }
        return null;
    }

    public static String normalizeCode(String text) {
        String code = text.toUpperCase(Locale.ROOT);
        if (code.startsWith("#")) {
            code = code.substring(1);
        }
        if (code.startsWith(CODE_PREFIX)) {
            code = code.substring(CODE_PREFIX.length());
        }
        if (code.length() != CODE_LENGTH) {
            return null;
        }
        for (int i = 0; i < code.length(); i++) {
            if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0) {
                return null;
            }
        }
        return code;
    }

    public void unblock(long key) {
        entries.remove(key);
    }

    public int purgeExpired() {
        long now = clock.getAsLong();
        int before = entries.size();
        entries.values().removeIf(packed -> expiryOf(packed) <= now);
        return before - entries.size();
    }

    public int clear() {
        int removed = entries.size();
        entries.clear();
        return removed;
    }

    public int size() {
        return entries.size();
    }

    String codeOf(long key) {
        long hash = SubnetKey.mix(key ^ codeSalt);
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = CODE_ALPHABET.charAt((int) (hash & 31));
            hash >>>= 5;
        }
        return new String(chars);
    }

    private Entry view(long key, long packed) {
        return new Entry(expiryOf(packed), REASONS[(int) (packed & 3)], codeOf(key), SubnetKey.describe(key));
    }

    private static long pack(long expiry, Reason reason) {
        return (expiry << 2) | reason.ordinal();
    }

    private static long expiryOf(long packed) {
        return packed >>> 2;
    }
}
