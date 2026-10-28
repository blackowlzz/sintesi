package dev.sintesi.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.sintesi.api.AttackState;
import dev.sintesi.config.MetricsConfig;
import dev.sintesi.net.Admission;
import dev.sintesi.net.SintesiCore;
import dev.sintesi.stats.Statistics.Snapshot;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.Executors;

final class MetricsServer implements AutoCloseable {

    private final SintesiRuntime runtime;
    private final MetricsConfig config;
    private final HttpServer server;

    private MetricsServer(SintesiRuntime runtime, MetricsConfig config) throws IOException {
        this.runtime = runtime;
        this.config = config;
        this.server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 16);
        server.createContext("/metrics", this::handle);
        server.setExecutor(Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "sintesi-metrics");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    static MetricsServer start(SintesiRuntime runtime, MetricsConfig config) throws IOException {
        return new MetricsServer(runtime, config);
    }

    MetricsConfig config() {
        return config;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!config.token().isEmpty() && !authorized(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            byte[] body = render().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private boolean authorized(String header) {
        byte[] expected = ("Bearer " + config.token()).getBytes(StandardCharsets.UTF_8);
        byte[] given = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given);
    }

    String render() {
        Snapshot snapshot = runtime.statistics().last();
        SintesiCore core = runtime.core();
        StringBuilder out = new StringBuilder(2048);
        counter(out, "sintesi_connections_total", "Connections seen", snapshot.totalConnections());
        counter(out, "sintesi_logins_total", "Login attempts", snapshot.totalJoins());
        counter(out, "sintesi_pings_total", "Server list pings answered", runtime.statistics().pingsTotal());
        counter(out, "sintesi_verifications_passed_total", "Verifications passed", snapshot.totalPassed());
        counter(out, "sintesi_verifications_failed_total", "Verifications failed", snapshot.totalFailed());
        counter(out, "sintesi_blocked_connections_total", "Connections refused", snapshot.totalBlocked());
        counter(out, "sintesi_bedrock_connections_total", "Bedrock connections not checked",
                runtime.statistics().bedrockTotal());
        gauge(out, "sintesi_connections_per_second", "New connections in the last second", snapshot.cps());
        gauge(out, "sintesi_verifying", "Verifications in progress", snapshot.verifying());
        gauge(out, "sintesi_limbo_sessions", "Limbo slots in use", core.gate().limboSessions());
        gauge(out, "sintesi_login_queue", "New players waiting in line", core.loginQueue().size());
        gauge(out, "sintesi_blocklist_size", "Blocked addresses and subnets", runtime.blocklist().size());
        gauge(out, "sintesi_verified_players", "Verified players stored", runtime.store().size());
        gauge(out, "sintesi_status_cache_entries", "Cached server list responses", core.statusCache().size());
        gauge(out, "sintesi_vpn_ranges", "VPN, proxy and hosting ranges loaded", runtime.vpnGuard().size());
        counter(out, "sintesi_vpn_refused_total", "Logins refused from VPN, proxy or hosting addresses",
                runtime.statistics().vpnRefusedTotal());
        gauge(out, "sintesi_flagged_client_kinds", "Client kinds that must solve the captcha", core.clusters().flagged());
        AttackState state = runtime.monitor().state();
        gauge(out, "sintesi_attack_state", "0 normal, 1 elevated, 2 attack", state.ordinal());
        out.append("# HELP sintesi_gate_decisions_total Gate decisions since the last reload\n");
        out.append("# TYPE sintesi_gate_decisions_total counter\n");
        for (Admission admission : Admission.values()) {
            out.append("sintesi_gate_decisions_total{decision=\"").append(admission.name().toLowerCase(Locale.ROOT))
                    .append("\"} ").append(core.gate().count(admission)).append('\n');
        }
        return out.toString();
    }

    private static void counter(StringBuilder out, String name, String help, long value) {
        metric(out, name, help, "counter", value);
    }

    private static void gauge(StringBuilder out, String name, String help, long value) {
        metric(out, name, help, "gauge", value);
    }

    private static void metric(StringBuilder out, String name, String help, String type, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        out.append(name).append(' ').append(value).append('\n');
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
