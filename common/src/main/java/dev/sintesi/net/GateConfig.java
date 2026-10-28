package dev.sintesi.net;

public record GateConfig(
        int tableSizeLog2,
        int globalBurst,
        int globalPerSecond,
        int subnetBurst,
        int subnetPerSecond,
        int maxConcurrentPerSubnet,
        int statusBurst,
        int statusPerSecond,
        int maxLimboSessions,
        boolean statusCache,
        int cachedStatusBurst,
        int cachedStatusPerSecond,
        int queueSize,
        long queueMillis) {

    public static GateConfig defaults() {
        return new GateConfig(16, 2000, 1000, 8, 3, 6, 2000, 1000, 5000, true, 10000, 5000, 10000, 15_000);
    }
}
