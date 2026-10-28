package dev.sintesi.net;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class ClusterTracker {

    public record Settings(boolean enabled, int minSessions, double factor, long flagMillis) {

        public static Settings defaults() {
            return new Settings(true, 15, 5.0, 10 * 60_000L);
        }
    }

    private static final int MAX_TYPES = 4096;
    private static final int MAX_FLAGGED = 4096;
    private static final long BUCKET_MILLIS = 10_000;
    private static final int BUCKETS = 6;
    private static final double BASELINE_WEIGHT = 1.0 / 60;
    private static final int SKETCH_ROWS = 3;
    private static final int SKETCH_WIDTH = 1 << 14;

    private static final class Baseline {
        double perMinute;
        int lastMinute;
    }

    private volatile Settings settings;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final ConcurrentHashMap<Long, Baseline> baselines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> flaggedUntil = new ConcurrentHashMap<>();
    private final int[][][] sketch = new int[BUCKETS][SKETCH_ROWS][SKETCH_WIDTH];
    private final long[] sketchStarts = new long[BUCKETS];

    public ClusterTracker(Settings settings, LongSupplier clock, Consumer<String> log) {
        this.settings = settings;
        this.clock = clock;
        this.log = log;
    }

    public void settings(Settings next) {
        this.settings = next;
    }

    public void record(long fingerprint) {
        Settings current = settings;
        if (!current.enabled() || fingerprint == 0L) {
            return;
        }
        long now = clock.getAsLong();
        int recent = sketchRecord(fingerprint, now);
        Baseline baseline = baselines.get(fingerprint);
        if (baseline == null && baselines.size() < MAX_TYPES) {
            baseline = baselines.computeIfAbsent(fingerprint, ignored -> new Baseline());
        }
        double normal = 0;
        if (baseline != null) {
            synchronized (baseline) {
                baseline.lastMinute++;
                normal = baseline.perMinute;
            }
        }
        if (recent < current.minSessions() || recent < current.factor() * Math.max(1.0, normal)) {
            return;
        }
        Long until = flaggedUntil.get(fingerprint);
        if (until == null && flaggedUntil.size() >= MAX_FLAGGED) {
            return;
        }
        flaggedUntil.put(fingerprint, now + current.flagMillis());
        if (until == null || until <= now) {
            log.accept("A wave of " + recent + " identical clients arrived in the last minute; clients of that kind"
                    + " must solve the captcha for the next " + current.flagMillis() / 60_000 + " minutes");
        }
    }

    public boolean requiresCaptcha(long[] fingerprints) {
        if (fingerprints == null) {
            return false;
        }
        for (long fingerprint : fingerprints) {
            if (requiresCaptcha(fingerprint)) {
                return true;
            }
        }
        return false;
    }

    public boolean requiresCaptcha(long fingerprint) {
        if (!settings.enabled() || fingerprint == 0L) {
            return false;
        }
        Long until = flaggedUntil.get(fingerprint);
        return until != null && until > clock.getAsLong();
    }

    public int flagged() {
        long now = clock.getAsLong();
        return (int) flaggedUntil.values().stream().filter(until -> until > now).count();
    }

    public void minuteTick() {
        long now = clock.getAsLong();
        flaggedUntil.values().removeIf(until -> until <= now);
        for (Map.Entry<Long, Baseline> entry : baselines.entrySet()) {
            Baseline baseline = entry.getValue();
            boolean remove;
            synchronized (baseline) {
                if (!flaggedUntil.containsKey(entry.getKey())) {
                    baseline.perMinute += BASELINE_WEIGHT * (baseline.lastMinute - baseline.perMinute);
                }
                remove = baseline.lastMinute == 0 && baseline.perMinute < 0.01;
                baseline.lastMinute = 0;
            }
            if (remove) {
                baselines.remove(entry.getKey(), baseline);
            }
        }
    }

    private synchronized int sketchRecord(long fingerprint, long now) {
        int slot = (int) ((now / BUCKET_MILLIS) % BUCKETS);
        long start = now - now % BUCKET_MILLIS;
        if (sketchStarts[slot] != start) {
            sketchStarts[slot] = start;
            for (int[] row : sketch[slot]) {
                Arrays.fill(row, 0);
            }
        }
        int estimate = 0;
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            if (now - sketchStarts[bucket] >= BUCKETS * BUCKET_MILLIS) {
                continue;
            }
            int least = Integer.MAX_VALUE;
            for (int row = 0; row < SKETCH_ROWS; row++) {
                int index = (int) (SubnetKey.mix(fingerprint + row * 0x9E3779B97F4A7C15L) >>> 40) & (SKETCH_WIDTH - 1);
                if (bucket == slot) {
                    sketch[bucket][row][index]++;
                }
                least = Math.min(least, sketch[bucket][row][index]);
            }
            estimate += least;
        }
        return estimate;
    }
}
