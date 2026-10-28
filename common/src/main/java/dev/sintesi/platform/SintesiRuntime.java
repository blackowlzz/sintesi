package dev.sintesi.platform;

import dev.sintesi.api.AttackState;
import dev.sintesi.attack.AttackMonitor;
import dev.sintesi.config.ConfigMigration;
import dev.sintesi.config.SintesiConfig;
import dev.sintesi.net.Blocklist;
import dev.sintesi.net.SintesiCore;
import dev.sintesi.stats.Statistics;
import dev.sintesi.stats.Statistics.Snapshot;
import dev.sintesi.store.EmbeddedStore;
import dev.sintesi.text.Messages;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class SintesiRuntime implements AutoCloseable {

    public interface Listener {
        default void second(Snapshot snapshot, AttackState state) {
        }

        default void attackStarted(Snapshot snapshot) {
        }

        default void attackEnded(AttackSummary summary) {
        }
    }

    public record AttackSummary(long durationMillis, long peakCps, long blocked, long failed, long passed) {
    }

    private static final String CONFIG_FILE = "config.yml";
    private static final String MESSAGES_FILE = "messages.yml";

    private final Path dataDirectory;
    private final Consumer<String> info;
    private final LongSupplier clock = System::currentTimeMillis;
    private final dev.sintesi.store.Store store;
    private final dev.sintesi.store.VerifiedKey keys;
    private final Blocklist blocklist;
    private final Statistics statistics;
    private final AttackMonitor monitor;
    private final ScheduledExecutorService scheduler;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private volatile SintesiCore core;
    private MetricsServer metrics;
    private final dev.sintesi.net.VpnGuard vpnGuard = new dev.sintesi.net.VpnGuard();
    private VpnLists vpnLists;
    private volatile dev.sintesi.net.ClusterTracker clusters;
    private dev.sintesi.net.LoginQueue loginQueue;
    private volatile java.util.function.Supplier<java.util.Collection<? extends PlatformPlayer>> players = java.util.List::of;
    private volatile java.util.Map<java.net.InetAddress, Integer> onlineByAddress = java.util.Map.of();
    private AttackState lastState = AttackState.NORMAL;
    private long attackStartedAt;
    private long attackPeak;
    private Snapshot attackStart;

    private SintesiRuntime(Path dataDirectory, Consumer<String> info, SintesiConfig config, Messages messages) {
        this.dataDirectory = dataDirectory;
        this.info = info;
        this.keys = dev.sintesi.store.VerifiedKey.loadOrCreate(dataDirectory.resolve("secret.key"));
        this.store = openStore(dataDirectory, config, keys.fingerprint());
        this.blocklist = new Blocklist(clock);
        this.statistics = new Statistics(clock.getAsLong());
        this.monitor = new AttackMonitor(config.attack(), clock);
        this.core = buildCore(config, messages);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "sintesi-maintenance");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static SintesiRuntime start(Path dataDirectory, Consumer<String> info) {
        try {
            Files.createDirectories(dataDirectory);
            SintesiConfig config = readConfig(dataDirectory, info);
            Messages messages = readMessages(dataDirectory, info, config.language());
            SintesiRuntime runtime = new SintesiRuntime(dataDirectory, info, config, messages);
            runtime.schedule();
            dev.sintesi.api.SintesiApi.bind(runtime.monitor()::state, info);
            runtime.applyMetrics(config.metrics());
            runtime.vpnLists().apply(config.vpn());
            return runtime;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public synchronized void reload() throws IOException {
        SintesiConfig config = readConfig(dataDirectory, info);
        Messages messages = readMessages(dataDirectory, info, config.language());
        monitor.thresholds(config.attack());
        core = buildCore(config, messages);
        applyMetrics(config.metrics());
        vpnLists().apply(config.vpn());
    }

    public void refreshVpnLists() {
        vpnLists().refreshNow();
    }

    public dev.sintesi.net.VpnGuard vpnGuard() {
        return vpnGuard;
    }

    private synchronized VpnLists vpnLists() {
        if (vpnLists == null) {
            vpnLists = new VpnLists(dataDirectory.resolve("vpn-lists"), vpnGuard, info, clock);
        }
        return vpnLists;
    }

    private synchronized void applyMetrics(dev.sintesi.config.MetricsConfig wanted) {
        if (metrics != null && metrics.config().equals(wanted)) {
            return;
        }
        if (metrics != null) {
            metrics.close();
            metrics = null;
        }
        if (!wanted.enabled()) {
            return;
        }
        try {
            metrics = MetricsServer.start(this, wanted);
            info.accept("Metrics available at http://" + wanted.bind() + ":" + wanted.port() + "/metrics");
        } catch (IOException | LinkageError failure) {
            info.accept("Metrics could not start on " + wanted.bind() + ":" + wanted.port() + ": " + failure);
        }
    }

    public void onClose(AutoCloseable closeable) {
        closeables.add(closeable);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public SintesiCore core() {
        return core;
    }

    public Messages messages() {
        return core.messages();
    }

    public Statistics statistics() {
        return statistics;
    }

    public Blocklist blocklist() {
        return blocklist;
    }

    public dev.sintesi.store.Store store() {
        return store;
    }

    public AttackMonitor monitor() {
        return monitor;
    }

    private dev.sintesi.store.Store openStore(Path directory, SintesiConfig config, long fingerprint) {
        dev.sintesi.config.DatabaseConfig database = config.database();
        if (database.enabled()) {
            try {
                java.sql.Driver driver = dev.sintesi.store.JdbcDriver.load(directory.resolve("libs"), info);
                java.util.Properties properties = new java.util.Properties();
                properties.setProperty("user", database.username());
                properties.setProperty("password", database.password());
                dev.sintesi.store.SqlStore shared = dev.sintesi.store.SqlStore.open(
                        () -> driver.connect(database.url(), properties), database.tablePrefix(), fingerprint,
                        database.syncMillis(), clock, info);
                info.accept("Verified players are shared through the database at " + database.host() + ":"
                        + database.port() + "/" + database.database() + " (" + shared.size() + " loaded)");
                return shared;
            } catch (IOException | java.sql.SQLException | RuntimeException failure) {
                info.accept("The database could not be used (" + failure.getMessage() + "); verified players are kept"
                        + " in " + config.storageFile() + " on this proxy only until the database works and Sintesi"
                        + " is restarted");
            }
        }
        return new EmbeddedStore(directory.resolve(config.storageFile()), clock, fingerprint);
    }

    public LongSupplier clock() {
        return clock;
    }

    public long uptimeMillis() {
        return statistics.uptimeMillis(clock.getAsLong());
    }

    private SintesiCore buildCore(SintesiConfig config, Messages messages) {
        SintesiCore built = new SintesiCore(config, messages, store, clock, blocklist, statistics, monitor, keys);
        built.vpnGuard(vpnGuard);
        if (clusters == null) {
            clusters = new dev.sintesi.net.ClusterTracker(config.clusters(), clock, info);
        } else {
            clusters.settings(config.clusters());
        }
        built.clusters(clusters);
        if (loginQueue == null) {
            loginQueue = new dev.sintesi.net.LoginQueue(config.gate().queueSize(), config.gate().queueMillis(), clock);
        } else {
            loginQueue.settings(config.gate().queueSize(), config.gate().queueMillis());
        }
        built.loginQueue(loginQueue);
        built.onlineCount(address -> onlineByAddress.getOrDefault(address, 0));
        built.logger(info);
        return built;
    }

    private void schedule() {
        scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> {
            store.flush();
            core.purgeExpired();
        }, 5, 5, TimeUnit.SECONDS);
        if (store instanceof EmbeddedStore embedded) {
            scheduler.scheduleAtFixedRate(embedded::compact, 30, 30, TimeUnit.MINUTES);
        }
        scheduler.scheduleAtFixedRate(() -> clusters.minuteTick(), 1, 1, TimeUnit.MINUTES);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                loginQueue.process();
            } catch (RuntimeException failure) {
                info.accept("Login queue error: " + failure);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
    }

    public void players(java.util.function.Supplier<java.util.Collection<? extends PlatformPlayer>> source) {
        this.players = source;
    }

    private void countOnline() {
        java.util.Map<java.net.InetAddress, Integer> counts = new java.util.HashMap<>();
        for (PlatformPlayer player : players.get()) {
            java.net.InetAddress address = player.address();
            if (address != null) {
                counts.merge(address, 1, Integer::sum);
            }
        }
        onlineByAddress = counts;
    }

    private void tick() {
        try {
            countOnline();
            long now = clock.getAsLong();
            Snapshot snapshot = statistics.sample(now);
            AttackState state = monitor.evaluate();
            if (state != lastState) {
                info.accept("Attack state changed from " + lastState + " to " + state
                        + " (" + snapshot.cps() + " connections per second)");
            }
            if (state == AttackState.ATTACK && lastState != AttackState.ATTACK) {
                attackStartedAt = now;
                attackPeak = snapshot.cps();
                attackStart = snapshot;
                listeners.forEach(listener -> listener.attackStarted(snapshot));
                dev.sintesi.api.SintesiApi.attackStarted(snapshot.cps());
            } else if (state == AttackState.ATTACK) {
                attackPeak = Math.max(attackPeak, snapshot.cps());
            } else if (lastState == AttackState.ATTACK && attackStart != null) {
                AttackSummary summary = new AttackSummary(now - attackStartedAt, attackPeak,
                        snapshot.totalBlocked() - attackStart.totalBlocked(),
                        snapshot.totalFailed() - attackStart.totalFailed(),
                        snapshot.totalPassed() - attackStart.totalPassed());
                info.accept("Attack ended after " + summary.durationMillis() / 1000 + " s, peak " + summary.peakCps()
                        + " cps, " + summary.blocked() + " connections blocked, " + summary.failed() + " failed verifications");
                listeners.forEach(listener -> listener.attackEnded(summary));
                dev.sintesi.api.SintesiApi.attackEnded(summary.durationMillis(), summary.peakCps(), summary.blocked(),
                        summary.failed(), summary.passed());
                attackStart = null;
            }
            lastState = state;
            listeners.forEach(listener -> listener.second(snapshot, state));
        } catch (RuntimeException failure) {
            info.accept("Sintesi maintenance error: " + failure);
        }
    }

    private static SintesiConfig readConfig(Path dataDirectory, Consumer<String> info) throws IOException {
        Path path = dataDirectory.resolve(CONFIG_FILE);
        String defaults;
        try (InputStream stream = SintesiRuntime.class.getResourceAsStream("/" + CONFIG_FILE)) {
            defaults = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!Files.exists(path)) {
            Files.writeString(path, defaults, StandardCharsets.UTF_8);
        }
        migrate(path, defaults, info);
        try (InputStream stream = Files.newInputStream(path)) {
            return SintesiConfig.load(stream);
        }
    }

    private static void migrate(Path path, String defaults, Consumer<String> info) throws IOException {
        ConfigMigration.Result result = ConfigMigration.migrate(defaults, Files.readString(path, StandardCharsets.UTF_8));
        if (result.text() == null) {
            return;
        }
        Files.copy(path, path.resolveSibling(path.getFileName() + ".old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(path, result.text(), StandardCharsets.UTF_8);
        info.accept(path.getFileName() + " updated with new options: " + String.join(", ", result.addedKeys())
                + " (previous file kept as " + path.getFileName() + ".old)");
    }

    private static Messages readMessages(Path dataDirectory, Consumer<String> info, String language) throws IOException {
        Path path = dataDirectory.resolve(MESSAGES_FILE);
        if (!Files.exists(path)) {
            Files.writeString(path, Messages.defaultSource(language), StandardCharsets.UTF_8);
        }
        migrate(path, Messages.defaultSource(language), info);
        Messages messages = Messages.load(Files.readString(path, StandardCharsets.UTF_8), language);
        if (!messages.unknownKeys().isEmpty()) {
            info.accept("messages.yml has unknown keys (ignored): " + String.join(", ", messages.unknownKeys()));
        }
        return messages;
    }

    @Override
    public synchronized void close() {
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception failure) {
                info.accept("Could not close " + closeable.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
        if (vpnLists != null) {
            vpnLists.close();
        }
        if (metrics != null) {
            metrics.close();
            metrics = null;
        }
        scheduler.shutdownNow();
        store.flush();
        store.close();
    }
}
