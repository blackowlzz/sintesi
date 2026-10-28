package dev.sintesi.net;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class ConnectionGate {

    private static final long GLOBAL_KEY = 0L;

    private final Blocklist blocklist;
    private final LongSupplier clock;
    private final TokenBucketTable loginBucket;
    private final TokenBucketTable statusBucket;
    private final TokenBucketTable cachedStatusBucket;
    private final TokenBucketTable subnetBuckets;
    private final AtomicIntegerArray concurrent;
    private final int concurrentMask;
    private final int maxConcurrent;
    private final AtomicInteger limboSessions = new AtomicInteger();
    private final int maxLimboSessions;
    private final TokenBucketTable floodBuckets;
    private final FloodBlockConfig.Mode floodMode;
    private final BooleanSupplier underAttack;
    private final LongAdder[] counters;

    public ConnectionGate(GateConfig config, Blocklist blocklist, LongSupplier clock) {
        this(config, FloodBlockConfig.disabled(), () -> false, blocklist, clock);
    }

    public ConnectionGate(GateConfig config, FloodBlockConfig flood, BooleanSupplier underAttack, Blocklist blocklist,
            LongSupplier clock) {
        this.blocklist = blocklist;
        this.clock = clock;
        this.loginBucket = new TokenBucketTable(1, config.globalBurst(), config.globalPerSecond());
        this.statusBucket = new TokenBucketTable(1, config.statusBurst(), config.statusPerSecond());
        this.cachedStatusBucket = new TokenBucketTable(1, config.cachedStatusBurst(), config.cachedStatusPerSecond());
        this.subnetBuckets = new TokenBucketTable(config.tableSizeLog2(), config.subnetBurst(), config.subnetPerSecond());
        this.concurrent = new AtomicIntegerArray(1 << config.tableSizeLog2());
        this.concurrentMask = (1 << config.tableSizeLog2()) - 1;
        this.maxConcurrent = config.maxConcurrentPerSubnet();
        this.maxLimboSessions = config.maxLimboSessions();
        this.floodMode = flood.mode();
        this.underAttack = underAttack;
        this.floodBuckets = flood.mode() == FloodBlockConfig.Mode.NEVER ? null
                : new TokenBucketTable(config.tableSizeLog2(), flood.connections(), flood.refillPerSecond());
        Admission[] values = Admission.values();
        this.counters = new LongAdder[values.length];
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new LongAdder();
        }
    }

    public Admission admit(long subnetKey) {
        return admit(subnetKey, subnetKey);
    }

    public Admission admit(long subnetKey, long addressKey) {
        return counted(evaluate(subnetKey, addressKey, false));
    }

    public Admission admit(long subnetKey, long addressKey, boolean skipFlood) {
        return counted(evaluate(subnetKey, addressKey, skipFlood));
    }

    public void release(long subnetKey) {
        concurrent.decrementAndGet(slotOf(subnetKey));
    }

    public Admission admitStatus() {
        return counted(statusBucket.tryAcquire(GLOBAL_KEY, clock.getAsLong()) ? Admission.ACCEPT : Admission.STATUS_LIMIT);
    }

    public Admission admitCachedStatus() {
        return counted(cachedStatusBucket.tryAcquire(GLOBAL_KEY, clock.getAsLong()) ? Admission.ACCEPT
                : Admission.STATUS_LIMIT);
    }

    public Admission admitLimbo() {
        return counted(tryLimbo());
    }

    public boolean admitQueuedLimbo() {
        Admission result = tryLimbo();
        if (result == Admission.ACCEPT) {
            counted(result);
            return true;
        }
        return false;
    }

    private Admission tryLimbo() {
        if (limboSessions.incrementAndGet() > maxLimboSessions) {
            limboSessions.decrementAndGet();
            return Admission.LIMBO_LIMIT;
        }
        if (!loginBucket.tryAcquire(GLOBAL_KEY, clock.getAsLong())) {
            limboSessions.decrementAndGet();
            return Admission.GLOBAL_LIMIT;
        }
        return Admission.ACCEPT;
    }

    public void releaseLimbo() {
        limboSessions.decrementAndGet();
    }

    public int limboSessions() {
        return limboSessions.get();
    }

    public long count(Admission admission) {
        return counters[admission.ordinal()].sum();
    }

    private Admission counted(Admission result) {
        counters[result.ordinal()].increment();
        return result;
    }

    private Admission evaluate(long subnetKey, long addressKey, boolean skipFlood) {
        if (blocklist.isBlocked(subnetKey) || blocklist.isBlocked(addressKey)) {
            return Admission.BLOCKED;
        }
        long now = clock.getAsLong();
        if (!skipFlood && floodBuckets != null && !floodBuckets.tryAcquire(addressKey, now)
                && (floodMode == FloodBlockConfig.Mode.ALWAYS || underAttack.getAsBoolean())) {
            return Admission.FLOOD;
        }
        if (!subnetBuckets.tryAcquire(subnetKey, now)) {
            return Admission.SUBNET_LIMIT;
        }
        if (concurrent.incrementAndGet(slotOf(subnetKey)) > maxConcurrent) {
            concurrent.decrementAndGet(slotOf(subnetKey));
            return Admission.CONCURRENT_LIMIT;
        }
        return Admission.ACCEPT;
    }

    private int slotOf(long subnetKey) {
        return (int) SubnetKey.mix(subnetKey) & concurrentMask;
    }
}
