package dev.sintesi.net;

import java.util.concurrent.atomic.AtomicLongArray;

public final class TokenBucketTable {

    private static final int MAX_TOKENS = 0xFFFF;

    private final AtomicLongArray slots;
    private final int mask;
    private final int capacity;
    private final int refillPerSecond;

    public TokenBucketTable(int sizeLog2, int capacity, int refillPerSecond) {
        if (sizeLog2 < 1 || sizeLog2 > 24) {
            throw new IllegalArgumentException("sizeLog2");
        }
        if (capacity < 1 || capacity > MAX_TOKENS || refillPerSecond < 1) {
            throw new IllegalArgumentException("bucket");
        }
        this.slots = new AtomicLongArray(1 << sizeLog2);
        this.mask = (1 << sizeLog2) - 1;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    public boolean tryAcquire(long key, long nowMillis) {
        long hash = SubnetKey.mix(key);
        int index = (int) hash & mask;
        int tag = (int) (hash >>> 48) & 0xFFFF;
        int now = (int) nowMillis;
        while (true) {
            long current = slots.get(index);
            long next;
            if (current == 0 || tagOf(current) != tag) {
                next = pack(tag, capacity - 1, now);
            } else {
                long elapsed = (now - timeOf(current)) & 0xFFFFFFFFL;
                long refilled = elapsed * refillPerSecond / 1000;
                int tokens = tokensOf(current);
                int timestamp = timeOf(current);
                if (refilled > 0) {
                    tokens = (int) Math.min(capacity, tokens + refilled);
                    timestamp = now;
                }
                if (tokens < 1) {
                    return false;
                }
                next = pack(tag, tokens - 1, timestamp);
            }
            if (slots.compareAndSet(index, current, next)) {
                return true;
            }
        }
    }

    public void clear() {
        for (int i = 0; i < slots.length(); i++) {
            slots.set(i, 0);
        }
    }

    private static long pack(int tag, int tokens, int timestamp) {
        return ((long) tag << 48) | ((long) tokens << 32) | (timestamp & 0xFFFFFFFFL);
    }

    private static int tagOf(long packed) {
        return (int) (packed >>> 48) & 0xFFFF;
    }

    private static int tokensOf(long packed) {
        return (int) (packed >>> 32) & 0xFFFF;
    }

    private static int timeOf(long packed) {
        return (int) packed;
    }
}
