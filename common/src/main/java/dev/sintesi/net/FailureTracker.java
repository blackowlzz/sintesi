package dev.sintesi.net;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;

public final class FailureTracker {

    private static final int SIZE_LOG2 = 16;
    private static final int MASK = (1 << SIZE_LOG2) - 1;

    private final AtomicLongArray slots = new AtomicLongArray(1 << SIZE_LOG2);
    private final Blocklist blocklist;
    private final LongSupplier clock;
    private final int threshold;
    private final long windowMillis;
    private final long blockMillis;

    public FailureTracker(Blocklist blocklist, LongSupplier clock, int threshold, long blockMillis) {
        this.blocklist = blocklist;
        this.clock = clock;
        this.threshold = threshold;
        this.windowMillis = blockMillis;
        this.blockMillis = blockMillis;
    }

    public boolean recordFailure(long addressKey) {
        if (threshold <= 0 || addressKey == 0L) {
            return false;
        }
        long hash = SubnetKey.mix(addressKey);
        int index = (int) hash & MASK;
        long tag = (hash >>> 48) & 0xFFFF;
        long nowSeconds = clock.getAsLong() / 1000;
        while (true) {
            long current = slots.get(index);
            long count = 1;
            if (current != 0 && (current >>> 48) == tag
                    && nowSeconds - (current & 0xFFFFFFFFL) <= windowMillis / 1000) {
                count = ((current >>> 32) & 0xFFFF) + 1;
            }
            boolean reached = count >= threshold;
            long next = reached ? 0L : (tag << 48) | (count << 32) | (nowSeconds & 0xFFFFFFFFL);
            if (slots.compareAndSet(index, current, next)) {
                if (reached) {
                    blocklist.block(addressKey, blockMillis, Blocklist.Reason.FAILURES);
                }
                return reached;
            }
        }
    }
}
