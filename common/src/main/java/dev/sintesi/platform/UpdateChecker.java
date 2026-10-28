package dev.sintesi.platform;

import dev.sintesi.api.AttackState;
import dev.sintesi.config.UpdateCheckConfig;
import dev.sintesi.stats.Statistics.Snapshot;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class UpdateChecker implements SintesiRuntime.Listener, AutoCloseable {

    public static final String PAGE = "https://modrinth.com/plugin/sintesi";
    private static final URI API = URI.create("https://api.modrinth.com/v2/project/sintesi/version?include_changelog=false");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_BYTES = 4 << 20;
    private static final long FIRST_CHECK_SECONDS = 10;
    private static final long NOTICE_DELAY_MILLIS = 3000;
    private static final long FRESH_MILLIS = 60_000;

    private final SintesiRuntime runtime;
    private final Platform platform;
    private final String version;
    private final URI api;
    private final Consumer<String> console;
    private final ScheduledExecutorService executor;
    private final HttpClient client;
    private final Map<UUID, Long> seen = new HashMap<>();
    private final Set<UUID> told = new HashSet<>();
    private volatile ModrinthVersions.Result result;
    private volatile long checkedAt;
    private UpdateCheckConfig applied;
    private ScheduledFuture<?> task;
    private String announced;
    private boolean failureLogged;
    private String toldFor;

    public UpdateChecker(SintesiRuntime runtime, Platform platform, String version, Consumer<String> console) {
        this(runtime, platform, version, console, API);
    }

    UpdateChecker(SintesiRuntime runtime, Platform platform, String version, Consumer<String> console, URI api) {
        this.runtime = runtime;
        this.platform = platform;
        this.version = version;
        this.console = console;
        this.api = api;
        this.executor = Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "sintesi-updates");
            thread.setDaemon(true);
            return thread;
        });
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public synchronized void apply(UpdateCheckConfig config) {
        if (config.equals(applied)) {
            return;
        }
        boolean channelChanged = applied != null && applied.channel() != config.channel();
        applied = config;
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (!config.enabled()) {
            result = null;
            return;
        }
        task = executor.scheduleWithFixedDelay(this::check, channelChanged ? 0 : FIRST_CHECK_SECONDS,
                TimeUnit.HOURS.toSeconds(config.intervalHours()), TimeUnit.SECONDS);
    }

    public boolean enabled() {
        UpdateCheckConfig config = applied;
        return config != null && config.enabled();
    }

    public String version() {
        return version;
    }

    ModrinthVersions.Result result() {
        return result;
    }

    void request(Consumer<ModrinthVersions.Result> done) {
        if (result != null && System.currentTimeMillis() - checkedAt < FRESH_MILLIS) {
            done.accept(result);
            return;
        }
        executor.execute(() -> {
            check();
            done.accept(result);
        });
    }

    @Override
    public void second(Snapshot snapshot, AttackState state) {
        apply(runtime.core().config().updates());
        ModrinthVersions.Result current = result;
        if (current == null || !current.outdated()) {
            seen.clear();
            told.clear();
            return;
        }
        if (!current.latest().number().equals(toldFor)) {
            told.clear();
            toldFor = current.latest().number();
        }
        long now = System.currentTimeMillis();
        Set<UUID> online = new HashSet<>();
        String message = null;
        for (PlatformPlayer player : platform.players()) {
            UUID id = player.uniqueId();
            online.add(id);
            if (told.contains(id) || now - seen.computeIfAbsent(id, key -> now) < NOTICE_DELAY_MILLIS) {
                continue;
            }
            told.add(id);
            if (!Permissions.has(player, Permissions.UPDATE)) {
                continue;
            }
            if (message == null) {
                message = runtime.messages().format("update.available", placeholders(current));
            }
            player.sendMessage(message);
        }
        seen.keySet().retainAll(online);
        told.retainAll(online);
    }

    Object[] placeholders(ModrinthVersions.Result current) {
        return new Object[] {"current", current.current(), "channel", current.channel().id(),
                "latest", current.outdated() ? current.latest().number() : current.current(),
                "behind", current.behind(), "url", url(current)};
    }

    private static String url(ModrinthVersions.Result current) {
        return current.outdated() ? PAGE + "/version/" + current.latest().id() : PAGE;
    }

    private void check() {
        UpdateCheckConfig config;
        synchronized (this) {
            config = applied;
        }
        if (config == null || !config.enabled()) {
            return;
        }
        try {
            ModrinthVersions.Result next = ModrinthVersions.evaluate(version, ModrinthVersions.parse(fetch()),
                    config.channel());
            result = next;
            checkedAt = System.currentTimeMillis();
            synchronized (this) {
                failureLogged = false;
                if (next.outdated() && !next.latest().number().equals(announced)) {
                    announced = next.latest().number();
                    console.accept("Update available: Sintesi " + next.latest().number() + " (you are running "
                            + version + ", " + next.behind() + (next.behind() == 1 ? " newer version" : " newer versions")
                            + " on the " + next.channel().id() + " channel). Download: " + url(next));
                }
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) {
                if (!failureLogged) {
                    failureLogged = true;
                    console.accept("Update check: could not reach Modrinth (" + failure.getMessage()
                            + "), trying again later");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String fetch() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(api).timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Sintesi/" + version + " (update checker; " + PAGE + ")")
                .header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                throw new IOException("the project is not published yet");
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] data = body.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new IOException("response too large");
            }
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
