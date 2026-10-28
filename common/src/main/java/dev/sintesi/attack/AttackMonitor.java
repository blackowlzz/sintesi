package dev.sintesi.attack;

import dev.sintesi.api.AttackState;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;

public final class AttackMonitor {

    private static final int WINDOW_SECONDS = 16;

    private final LongSupplier clock;
    private volatile AttackThresholds thresholds;
    private final AtomicLongArray counts = new AtomicLongArray(WINDOW_SECONDS);
    private final AtomicLongArray stamps = new AtomicLongArray(WINDOW_SECONDS);
    private volatile AttackState state = AttackState.NORMAL;
    private volatile long calmSince;

    public AttackMonitor(AttackThresholds thresholds, LongSupplier clock) {
        this.thresholds = thresholds;
        this.clock = clock;
    }

    public void thresholds(AttackThresholds thresholds) {
        this.thresholds = thresholds;
    }

    public void recordConnection() {
        long second = clock.getAsLong() / 1000;
        int slot = (int) (second % WINDOW_SECONDS);
        if (stamps.get(slot) != second) {
            stamps.set(slot, second);
            counts.set(slot, 0);
        }
        counts.incrementAndGet(slot);
    }

    public long connectionsPerSecond() {
        long second = clock.getAsLong() / 1000;
        long total = 0;
        int seconds = thresholds.windowSeconds();
        for (int i = 0; i < seconds; i++) {
            long target = second - i;
            int slot = (int) (target % WINDOW_SECONDS);
            if (stamps.get(slot) == target) {
                total += counts.get(slot);
            }
        }
        return total / seconds;
    }

    public AttackState evaluate() {
        long rate = connectionsPerSecond();
        long now = clock.getAsLong();
        AttackState current = state;
        AttackState target = rate >= thresholds.attackRate() ? AttackState.ATTACK
                : rate >= thresholds.elevatedRate() ? AttackState.ELEVATED
                : AttackState.NORMAL;
        if (target.ordinal() >= current.ordinal()) {
            calmSince = now;
            state = target;
            return target;
        }
        if (calmSince == 0) {
            calmSince = now;
        }
        if (now - calmSince >= thresholds.cooldownMillis()) {
            AttackState lowered = AttackState.values()[current.ordinal() - 1];
            state = lowered;
            calmSince = now;
            return lowered;
        }
        return current;
    }

    public AttackState state() {
        return state;
    }
}
