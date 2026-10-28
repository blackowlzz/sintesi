package dev.sintesi.config;

import java.util.List;

public record VpnConfig(Mode mode, List<String> sources, int refreshHours, List<String> blockedRanges,
        List<String> allowedRanges) {

    public enum Mode {
        NEVER,
        DURING_ATTACK,
        ALWAYS
    }

    public static final List<String> DEFAULT_SOURCES = List.of(
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/vpn/ipv4.txt",
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv4.txt",
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/vpn/ipv6.txt",
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv6.txt",
            "https://check.torproject.org/torbulkexitlist");
}
