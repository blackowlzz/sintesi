package dev.sintesi.stats;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class Statistics {

    public record Snapshot(
            long cps,
            long joins,
            long pings,
            long passed,
            long failed,
            long blocked,
            int verifying,
            long peakCps,
            long totalConnections,
            long totalJoins,
            long totalPassed,
            long totalFailed,
            long totalBlocked) {

        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private final LongAdder connections = new LongAdder();
    private final LongAdder joins = new LongAdder();
    private final LongAdder pings = new LongAdder();
    private final LongAdder passed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder blocked = new LongAdder();
    private final LongAdder bedrock = new LongAdder();
    private final LongAdder vpnRefused = new LongAdder();
    private final AtomicInteger verifying = new AtomicInteger();
    private final long startedAt;
    private long lastSampleAt;
    private long[] lastTotals = new long[6];
    private long peakCps;
    private volatile Snapshot last = Snapshot.EMPTY;

    public Statistics(long now) {
        this.startedAt = now;
        this.lastSampleAt = now;
    }

    public void connection() {
        connections.increment();
    }

    public void join() {
        joins.increment();
    }

    public void ping() {
        pings.increment();
    }

    public void blocked() {
        blocked.increment();
    }

    public void bedrock() {
        bedrock.increment();
    }

    public void vpnRefused() {
        vpnRefused.increment();
    }

    public long vpnRefusedTotal() {
        return vpnRefused.sum();
    }

    public long pingsTotal() {
        return pings.sum();
    }

    public long bedrockTotal() {
        return bedrock.sum();
    }

    public void verificationStarted() {
        verifying.incrementAndGet();
    }

    public void verificationFinished(boolean success) {
        verifying.decrementAndGet();
        if (success) {
            passed.increment();
        } else {
            failed.increment();
        }
    }

    public void verificationAbandoned() {
        verifying.decrementAndGet();
    }

    public synchronized Snapshot sample(long now) {
        long[] totals = {connections.sum(), joins.sum(), pings.sum(), passed.sum(), failed.sum(), blocked.sum()};
        long elapsed = Math.max(1, now - lastSampleAt);
        long[] rates = new long[totals.length];
        for (int i = 0; i < totals.length; i++) {
            rates[i] = Math.round((totals[i] - lastTotals[i]) * 1000.0 / elapsed);
        }
        lastTotals = totals;
        lastSampleAt = now;
        peakCps = Math.max(peakCps, rates[0]);
        last = new Snapshot(rates[0], rates[1], rates[2], rates[3], rates[4], rates[5], Math.max(0, verifying.get()),
                peakCps, totals[0], totals[1], totals[3], totals[4], totals[5]);
        return last;
    }

    public Snapshot last() {
        return last;
    }

    public long uptimeMillis(long now) {
        return now - startedAt;
    }
}
