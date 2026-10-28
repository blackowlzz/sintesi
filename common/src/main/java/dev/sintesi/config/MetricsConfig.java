package dev.sintesi.config;

public record MetricsConfig(boolean enabled, String bind, int port, String token) {

    public static MetricsConfig defaults() {
        return new MetricsConfig(false, "127.0.0.1", 9225, "");
    }
}
