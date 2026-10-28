package dev.sintesi.net;

public record FloodBlockConfig(
        Mode mode,
        int connections,
        int windowSeconds,
        long blockMillis,
        long noticeIntervalMillis,
        boolean allowVerifiedPlayers) {

    public enum Mode {
        NEVER,
        DURING_ATTACK,
        ALWAYS
    }

    public static FloodBlockConfig defaults() {
        return new FloodBlockConfig(Mode.DURING_ATTACK, 30, 5, 30 * 60_000L, 2_000L, false);
    }

    public static FloodBlockConfig disabled() {
        return new FloodBlockConfig(Mode.NEVER, 30, 5, 30 * 60_000L, 2_000L, false);
    }

    public int refillPerSecond() {
        return Math.max(1, connections / windowSeconds);
    }
}
