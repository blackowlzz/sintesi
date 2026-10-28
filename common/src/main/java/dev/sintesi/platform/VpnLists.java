package dev.sintesi.platform;

import dev.sintesi.config.VpnConfig;
import dev.sintesi.net.IpRangeSet;
import dev.sintesi.net.VpnGuard;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class VpnLists implements AutoCloseable {

    private static final int MAX_BYTES = 32 << 20;
    private static final int MIN_V4_PREFIX = 8;
    private static final int MIN_V6_PREFIX = 16;
    private static final double MAX_COVERAGE = 0.25;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final Path directory;
    private final VpnGuard guard;
    private final Consumer<String> info;
    private final LongSupplier clock;
    private final ScheduledExecutorService executor;
    private VpnConfig config;
    private ScheduledFuture<?> refresh;
    private String lastSummary;

    VpnLists(Path directory, VpnGuard guard, Consumer<String> info, LongSupplier clock) {
        this.directory = directory;
        this.guard = guard;
        this.info = info;
        this.clock = clock;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "sintesi-vpn-lists");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized void apply(VpnConfig next) {
        boolean sourcesChanged = config == null || !config.sources().equals(next.sources())
                || config.refreshHours() != next.refreshHours() || config.mode() != next.mode();
        config = next;
        rebuild();
        if (!sourcesChanged) {
            return;
        }
        if (refresh != null) {
            refresh.cancel(false);
            refresh = null;
        }
        if (next.mode() != VpnConfig.Mode.NEVER && !next.sources().isEmpty()) {
            refresh = executor.scheduleWithFixedDelay(this::refresh, 0, next.refreshHours(), TimeUnit.HOURS);
        }
    }

    void refreshNow() {
        executor.execute(() -> refresh(true));
    }

    private void refresh() {
        refresh(false);
    }

    private void refresh(boolean force) {
        VpnConfig current;
        synchronized (this) {
            current = config;
        }
        if (current == null || current.mode() == VpnConfig.Mode.NEVER) {
            return;
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        boolean changed = false;
        for (String source : current.sources()) {
            Path cache = cacheFile(source);
            try {
                if (!force && Files.exists(cache) && clock.getAsLong() - Files.getLastModifiedTime(cache).toMillis()
                        < TimeUnit.HOURS.toMillis(current.refreshHours())) {
                    continue;
                }
                download(client, source, cache);
                changed = true;
            } catch (IOException | RuntimeException failure) {
                info.accept("VPN block: could not download " + source + " (" + failure.getMessage() + ")"
                        + (Files.exists(cache) ? ", keeping the previous copy" : ""));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (changed || force) {
            synchronized (this) {
                if (force) {
                    lastSummary = null;
                }
                rebuild();
            }
        }
    }

    private void download(HttpClient client, String source, Path cache) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source)).timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Sintesi").GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] data = body.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new IOException("larger than " + (MAX_BYTES >> 20) + " MB");
            }
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "download", ".tmp");
            try {
                Files.write(temporary, data);
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private void rebuild() {
        if (config.mode() == VpnConfig.Mode.NEVER) {
            guard.update(IpRangeSet.EMPTY, IpRangeSet.EMPTY);
            return;
        }
        IpRangeSet.Builder blocked = IpRangeSet.builder().rejectBroaderThan(MIN_V4_PREFIX, MIN_V6_PREFIX);
        int loaded = 0;
        for (String source : config.sources()) {
            Path cache = cacheFile(source);
            if (!Files.exists(cache)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
                if (!plausible(source, lines)) {
                    continue;
                }
                for (String line : lines) {
                    blocked.add(line);
                }
                loaded++;
            } catch (IOException | RuntimeException failure) {
                info.accept("VPN block: could not read the copy of " + source + " (" + failure.getMessage() + ")");
            }
        }
        blocked.rejectBroaderThan(0, 0);
        config.blockedRanges().forEach(blocked::add);
        IpRangeSet.Builder allowed = IpRangeSet.builder();
        config.allowedRanges().forEach(allowed::add);
        IpRangeSet built = blocked.build();
        guard.update(built, allowed.build());
        String summary = "VPN block: " + built.size() + " ranges from " + loaded + " of " + config.sources().size()
                + " lists" + (config.blockedRanges().isEmpty() ? "" : " plus " + config.blockedRanges().size()
                        + " configured ranges");
        if (!summary.equals(lastSummary) && (loaded > 0 || !config.blockedRanges().isEmpty())) {
            info.accept(summary);
            lastSummary = summary;
        }
    }

    private boolean plausible(String source, List<String> lines) {
        IpRangeSet.Builder check = IpRangeSet.builder().rejectBroaderThan(MIN_V4_PREFIX, MIN_V6_PREFIX);
        lines.forEach(check::add);
        IpRangeSet set = check.build();
        if (check.tooBroad() > 0) {
            info.accept("VPN block: ignoring " + check.tooBroad() + " ranges wider than /" + MIN_V4_PREFIX + " in " + source);
        }
        if (set.ipv4Coverage() > MAX_COVERAGE) {
            info.accept("VPN block: " + source + " would cover " + Math.round(set.ipv4Coverage() * 100)
                    + "% of all IPv4 addresses, so it is not used");
            return false;
        }
        return true;
    }

    private Path cacheFile(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest, 0, 8) + ".txt");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    List<Path> cacheFiles() {
        return config.sources().stream().map(this::cacheFile).toList();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
