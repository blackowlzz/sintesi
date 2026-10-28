package dev.sintesi.config;

import dev.sintesi.attack.AttackThresholds;
import dev.sintesi.net.FloodBlockConfig;
import dev.sintesi.net.GateConfig;
import dev.sintesi.net.ProxyTrust;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public record SintesiConfig(
        GateConfig gate,
        AttackThresholds attack,
        int minProtocol,
        int maxProtocol,
        List<String> requiredHostnames,
        long verifiedLifetimeMillis,
        long verificationTimeoutMillis,
        boolean allowUnsupportedProtocols,
        boolean transfer,
        int maxSessionsPerAddress,
        boolean protocolChecks,
        boolean vehicleCheck,
        boolean visiblePlatform,
        CaptchaMode captcha,
        long captchaMillis,
        int captchaAttempts,
        double physicsTolerance,
        int compressionThreshold,
        int blacklistThreshold,
        long blacklistMillis,
        boolean exemptLocalConnections,
        boolean logFailureReasons,
        List<String> trustedGeyserAddresses,
        String storageFile,
        FloodBlockConfig floodBlock,
        ProxyTrust trustedProxies,
        MetricsConfig metrics,
        VpnConfig vpn,
        dev.sintesi.net.ClusterTracker.Settings clusters,
        DatabaseConfig database,
        boolean logAddresses,
        int maxOnlinePerAddress,
        WebhookConfig webhook,
        boolean verifyOnlyDuringAttacks,
        boolean captchaOnFallFailure,
        java.util.regex.Pattern namePattern,
        java.util.regex.Pattern brandPattern,
        String transferHost,
        int transferPort,
        java.util.Set<Integer> blockedProtocols,
        String language,
        UpdateCheckConfig updates) {

    public enum CaptchaMode {
        NEVER,
        DURING_ATTACK,
        ALWAYS
    }

    public static SintesiConfig load(InputStream stream) {
        try (stream) {
            return parse(new Section("", MiniYaml.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8))));
        } catch (IOException exception) {
            throw new ConfigException(exception.getMessage());
        }
    }

    private static java.util.regex.Pattern pattern(Section section, String key, String fallback) {
        String text = section.string(key, fallback);
        try {
            return java.util.regex.Pattern.compile(text);
        } catch (java.util.regex.PatternSyntaxException invalid) {
            throw new ConfigException("verification." + key + " is not a valid regular expression: " + invalid.getDescription());
        }
    }

    public static SintesiConfig parse(Section root) {
        GateConfig defaults = GateConfig.defaults();
        Section gateSection = root.section("gate");
        GateConfig gate = new GateConfig(
                gateSection.integer("table-size-log2", defaults.tableSizeLog2(), 8, 22),
                gateSection.integer("global-burst", defaults.globalBurst(), 1, 65535),
                gateSection.integer("global-per-second", defaults.globalPerSecond(), 1, 1_000_000),
                gateSection.integer("subnet-burst", defaults.subnetBurst(), 1, 65535),
                gateSection.integer("subnet-per-second", defaults.subnetPerSecond(), 1, 1_000_000),
                gateSection.integer("max-concurrent-per-subnet", defaults.maxConcurrentPerSubnet(), 1, 1000),
                gateSection.integer("status-burst", defaults.statusBurst(), 1, 65535),
                gateSection.integer("status-per-second", defaults.statusPerSecond(), 1, 1_000_000),
                gateSection.integer("max-limbo-sessions", defaults.maxLimboSessions(), 1, 1_000_000),
                gateSection.bool("status-cache", defaults.statusCache()),
                gateSection.integer("cached-status-burst", defaults.cachedStatusBurst(), 1, 65535),
                gateSection.integer("cached-status-per-second", defaults.cachedStatusPerSecond(), 1, 1_000_000),
                gateSection.integer("queue-size", defaults.queueSize(), 0, 1_000_000),
                gateSection.integer("queue-seconds", (int) (defaults.queueMillis() / 1000), 1, 25) * 1000L);
        gateSection.rejectUnknown();

        FloodBlockConfig floodDefaults = FloodBlockConfig.defaults();
        Section floodSection = root.section("flood-block");
        String floodModeText = floodSection.string("mode", "during-attack");
        FloodBlockConfig.Mode floodMode = switch (floodModeText) {
            case "never" -> FloodBlockConfig.Mode.NEVER;
            case "during-attack" -> FloodBlockConfig.Mode.DURING_ATTACK;
            case "always" -> FloodBlockConfig.Mode.ALWAYS;
            default -> throw new ConfigException("flood-block.mode must be never, during-attack or always");
        };
        FloodBlockConfig floodBlock = new FloodBlockConfig(floodMode,
                floodSection.integer("connections", floodDefaults.connections(), 10, 65535),
                floodSection.integer("window-seconds", floodDefaults.windowSeconds(), 1, 60),
                floodSection.longValue("minutes", floodDefaults.blockMillis() / 60_000, 1, 10_080) * 60_000L,
                floodSection.longValue("notice-interval-seconds", floodDefaults.noticeIntervalMillis() / 1000, 1, 60) * 1000L,
                floodSection.bool("allow-verified-players", floodDefaults.allowVerifiedPlayers()));
        floodSection.rejectUnknown();

        AttackThresholds attackDefaults = AttackThresholds.defaults();
        Section attackSection = root.section("attack");
        int elevated = attackSection.integer("elevated-rate", attackDefaults.elevatedRate(), 1, 1_000_000);
        int attackRate = attackSection.integer("attack-rate", attackDefaults.attackRate(), 2, 1_000_000);
        if (attackRate <= elevated) {
            throw new ConfigException("attack.attack-rate must be greater than attack.elevated-rate");
        }
        AttackThresholds attack = new AttackThresholds(
                attackSection.integer("window-seconds", attackDefaults.windowSeconds(), 1, 16),
                elevated,
                attackRate,
                attackSection.longValue("cooldown-seconds", attackDefaults.cooldownMillis() / 1000, 1, 86_400) * 1000);
        attackSection.rejectUnknown();

        Section protocol = root.section("protocol");
        int min = protocol.integer("min", 47, 0, Integer.MAX_VALUE);
        int max = protocol.integer("max", 777, min, Integer.MAX_VALUE);
        List<String> hosts = protocol.strings("required-hostnames").stream().map(String::toLowerCase).toList();
        java.util.Set<Integer> blockedProtocols = new java.util.HashSet<>();
        for (String number : protocol.strings("blocked")) {
            try {
                blockedProtocols.add(Integer.parseInt(number.trim()));
            } catch (NumberFormatException invalid) {
                throw new ConfigException("protocol.blocked must contain protocol numbers, got " + number);
            }
        }
        protocol.rejectUnknown();

        Section verification = root.section("verification");
        long lifetime = verification.longValue("verified-lifetime-hours", 72, 1, 8760) * 3_600_000L;
        long timeout = verification.longValue("timeout-seconds", 20, 2, 120) * 1000L;
        boolean allowUnsupported = verification.bool("allow-unsupported-protocols", false);
        boolean transfer = verification.bool("transfer", true);
        int maxSessions = verification.integer("max-sessions-per-address", 1, 1, 100);
        int maxOnline = verification.integer("max-online-per-address", 3, 0, 1000);
        String verificationMode = verification.string("mode", "always");
        if (!verificationMode.equals("always") && !verificationMode.equals("during-attack")) {
            throw new ConfigException("verification.mode must be always or during-attack");
        }
        boolean fallCaptcha = verification.bool("captcha-on-fall-failure", false);
        java.util.regex.Pattern namePattern = pattern(verification, "valid-name-regex", "[A-Za-z0-9_]{3,16}");
        java.util.regex.Pattern brandPattern = pattern(verification, "valid-brand-regex", "[ -~\u00a7]+");
        String transferHost = verification.string("transfer-host", "");
        if (!transferHost.isEmpty() && !transferHost.matches("[A-Za-z0-9.-]{1,253}")) {
            throw new ConfigException("verification.transfer-host must be a host name or an IP address");
        }
        int transferPort = verification.integer("transfer-port", 0, 0, 65535);
        boolean protocolChecks = verification.bool("protocol-checks", true);
        boolean vehicleCheck = verification.bool("vehicle-check", true);
        boolean visiblePlatform = verification.bool("visible-platform", false);
        String captchaText = verification.string("captcha", "during-attack");
        CaptchaMode captcha = switch (captchaText) {
            case "never" -> CaptchaMode.NEVER;
            case "during-attack" -> CaptchaMode.DURING_ATTACK;
            case "always" -> CaptchaMode.ALWAYS;
            default -> throw new ConfigException("verification.captcha must be never, during-attack or always");
        };
        long captchaMillis = verification.longValue("captcha-seconds", 60, 15, 300) * 1000L;
        int captchaAttempts = verification.integer("captcha-attempts", 3, 1, 10);
        double tolerance = verification.decimal("physics-tolerance", 1.0E-7, 1.0E-9, 0.1);
        int compressionThreshold = verification.integer("compression-threshold", 256, -1, 65535);
        int blacklistThreshold = verification.integer("blacklist-threshold", 2, 0, 100);
        long blacklistMillis = verification.longValue("blacklist-minutes", 10, 1, 10_080) * 60_000L;
        verification.rejectUnknown();

        Section logging = root.section("logging");
        boolean failureReasons = logging.bool("failure-reasons", false);
        boolean logAddresses = logging.bool("player-addresses", true);
        logging.rejectUnknown();

        Section bedrock = root.section("bedrock");
        boolean exemptLocal = bedrock.bool("exempt-local-connections", true);
        List<String> geyser = bedrock.strings("trusted-geyser-addresses");
        for (String address : geyser) {
            if (dev.sintesi.net.NumericAddress.parse(address) == null) {
                throw new ConfigException("bedrock.trusted-geyser-addresses must contain IP addresses, got " + address);
            }
        }
        bedrock.rejectUnknown();

        Section proxySection = root.section("proxy-protocol");
        ProxyTrust trustedProxies = ProxyTrust.parse(proxySection.strings("trusted-addresses"));
        proxySection.rejectUnknown();

        Section vpnSection = root.section("vpn-block");
        String vpnModeText = vpnSection.string("mode", "during-attack");
        VpnConfig.Mode vpnMode = switch (vpnModeText) {
            case "never" -> VpnConfig.Mode.NEVER;
            case "during-attack" -> VpnConfig.Mode.DURING_ATTACK;
            case "always" -> VpnConfig.Mode.ALWAYS;
            default -> throw new ConfigException("vpn-block.mode must be never, during-attack or always");
        };
        List<String> vpnSources = vpnSection.has("sources") ? vpnSection.strings("sources") : VpnConfig.DEFAULT_SOURCES;
        for (String source : vpnSources) {
            if (!source.startsWith("https://")) {
                throw new ConfigException("vpn-block.sources must contain https addresses, got " + source);
            }
        }
        List<String> vpnBlocked = vpnSection.strings("blocked-ranges");
        List<String> vpnAllowed = vpnSection.strings("allowed-ranges");
        for (List<String> ranges : List.of(vpnBlocked, vpnAllowed)) {
            for (String range : ranges) {
                if (!dev.sintesi.net.IpRangeSet.builder().add(range)) {
                    throw new ConfigException("vpn-block ranges must be IP addresses or ranges, got " + range);
                }
            }
        }
        VpnConfig vpn = new VpnConfig(vpnMode, List.copyOf(vpnSources),
                vpnSection.integer("refresh-hours", 24, 1, 720), vpnBlocked, vpnAllowed);
        vpnSection.rejectUnknown();

        dev.sintesi.net.ClusterTracker.Settings clusterDefaults = dev.sintesi.net.ClusterTracker.Settings.defaults();
        Section clusterSection = root.section("clusters");
        dev.sintesi.net.ClusterTracker.Settings clusters = new dev.sintesi.net.ClusterTracker.Settings(
                clusterSection.bool("enabled", clusterDefaults.enabled()),
                clusterSection.integer("min-sessions", clusterDefaults.minSessions(), 3, 100_000),
                clusterSection.decimal("factor", clusterDefaults.factor(), 1.5, 1000),
                clusterSection.longValue("minutes", clusterDefaults.flagMillis() / 60_000, 1, 1440) * 60_000L);
        clusterSection.rejectUnknown();

        MetricsConfig metricsDefaults = MetricsConfig.defaults();
        Section metricsSection = root.section("metrics");
        String metricsBind = metricsSection.string("bind", metricsDefaults.bind());
        if (dev.sintesi.net.NumericAddress.parse(metricsBind) == null) {
            throw new ConfigException("metrics.bind must be an IP address, got " + metricsBind);
        }
        MetricsConfig metrics = new MetricsConfig(metricsSection.bool("enabled", metricsDefaults.enabled()), metricsBind,
                metricsSection.integer("port", metricsDefaults.port(), 1, 65535),
                metricsSection.string("token", metricsDefaults.token()));
        metricsSection.rejectUnknown();

        Section webhookSection = root.section("webhook");
        String webhookUrl = webhookSection.string("url", "");
        if (!webhookUrl.isEmpty() && !webhookUrl.startsWith("https://")) {
            throw new ConfigException("webhook.url must be empty or an https address");
        }
        WebhookConfig webhook = new WebhookConfig(webhookUrl, webhookSection.string("username", "Sintesi"),
                webhookSection.bool("on-attack-start", true), webhookSection.bool("on-attack-end", true));
        webhookSection.rejectUnknown();

        Section storage = root.section("storage");
        String file = storage.string("file", "verified.bin");
        String storageType = storage.string("type", "file");
        if (!storageType.equals("file") && !storageType.equals("mysql")) {
            throw new ConfigException("storage.type must be file or mysql");
        }
        Section mysql = storage.section("mysql");
        String host = mysql.string("host", "127.0.0.1");
        if (!host.matches("[A-Za-z0-9.:\\[\\]_-]{1,253}")) {
            throw new ConfigException("storage.mysql.host is not a valid host name or address");
        }
        String databaseName = mysql.string("database", "sintesi");
        String prefix = mysql.string("table-prefix", "sintesi_");
        if (!databaseName.matches("[A-Za-z0-9_]{1,64}") || !prefix.matches("[A-Za-z0-9_]{0,24}")) {
            throw new ConfigException("storage.mysql.database and table-prefix may only contain letters, digits and _");
        }
        String sslMode = mysql.string("ssl-mode", "disable");
        if (!java.util.Set.of("disable", "trust", "verify-ca", "verify-full").contains(sslMode)) {
            throw new ConfigException("storage.mysql.ssl-mode must be disable, trust, verify-ca or verify-full");
        }
        DatabaseConfig database = new DatabaseConfig(storageType.equals("mysql"), host,
                mysql.integer("port", 3306, 1, 65535), databaseName, mysql.string("username", "sintesi"),
                mysql.string("password", ""), prefix, sslMode, mysql.integer("sync-seconds", 5, 1, 300) * 1000L);
        mysql.rejectUnknown();
        storage.rejectUnknown();

        Section updateSection = root.section("update-checker");
        String updateChannelText = updateSection.string("channel", "auto");
        UpdateCheckConfig.Channel updateChannel = switch (updateChannelText) {
            case "auto" -> UpdateCheckConfig.Channel.AUTO;
            case "release" -> UpdateCheckConfig.Channel.RELEASE;
            case "beta" -> UpdateCheckConfig.Channel.BETA;
            case "alpha" -> UpdateCheckConfig.Channel.ALPHA;
            default -> throw new ConfigException("update-checker.channel must be auto, release, beta or alpha");
        };
        UpdateCheckConfig updates = new UpdateCheckConfig(updateSection.bool("enabled", true),
                updateSection.integer("interval-hours", 6, 1, 168), updateChannel);
        updateSection.rejectUnknown();

        String language = root.string("language", "en");
        if (!dev.sintesi.text.Messages.LANGUAGES.contains(language)) {
            throw new ConfigException("language must be one of " + String.join(", ", dev.sintesi.text.Messages.LANGUAGES));
        }
        root.rejectUnknown();
        return new SintesiConfig(gate, attack, min, max, hosts, lifetime, timeout, allowUnsupported, transfer, maxSessions, protocolChecks, vehicleCheck, visiblePlatform, captcha, captchaMillis, captchaAttempts,
                tolerance, compressionThreshold,
                blacklistThreshold, blacklistMillis, exemptLocal, failureReasons, geyser, file, floodBlock, trustedProxies, metrics, vpn, clusters, database, logAddresses, maxOnline, webhook,
                verificationMode.equals("during-attack"), fallCaptcha, namePattern, brandPattern, transferHost, transferPort,
                java.util.Set.copyOf(blockedProtocols), language, updates);
    }
}
