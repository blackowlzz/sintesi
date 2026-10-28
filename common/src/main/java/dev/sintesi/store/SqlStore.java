package dev.sintesi.store;

import dev.sintesi.util.LongLongMap;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class SqlStore implements Store {

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    private static final int STRIPES = 64;
    private static final int MAX_PENDING = 100_000;
    private static final long SYNC_MARGIN_MILLIS = 60_000;
    private static final long PURGE_AFTER_MILLIS = 86_400_000L;
    private static final long ERROR_LOG_INTERVAL_MILLIS = 60_000;

    private final ConnectionFactory factory;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final String table;
    private final String meta;
    private final LongLongMap[] stripes = new LongLongMap[STRIPES];
    private final LongLongMap transfers = new LongLongMap();
    private final ScheduledExecutorService executor;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong lastErrorLog = new AtomicLong();
    private Connection connection;
    private long lastSync;
    private long clearedSeen;

    private SqlStore(ConnectionFactory factory, String prefix, LongSupplier clock, Consumer<String> log) {
        this.factory = factory;
        this.clock = clock;
        this.log = log;
        this.table = prefix + "verified";
        this.meta = prefix + "meta";
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new LongLongMap();
        }
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "sintesi-database");
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        this.executor = pool;
    }

    public static SqlStore open(ConnectionFactory factory, String prefix, long fingerprint, long syncMillis,
            LongSupplier clock, Consumer<String> log) throws SQLException {
        SqlStore store = new SqlStore(factory, prefix, clock, log);
        try {
            store.initialize(fingerprint);
        } catch (SQLException failure) {
            store.close();
            throw failure;
        }
        store.executor.scheduleWithFixedDelay(store::syncQuietly, syncMillis, syncMillis, TimeUnit.MILLISECONDS);
        store.executor.scheduleWithFixedDelay(store::purgeQuietly, 1, 60, TimeUnit.MINUTES);
        return store;
    }

    private void initialize(long fingerprint) throws SQLException {
        Connection db = connection();
        try (Statement statement = db.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (vkey BIGINT NOT NULL PRIMARY KEY,"
                    + " expiry BIGINT NOT NULL, transfer_until BIGINT NOT NULL, updated BIGINT NOT NULL,"
                    + " KEY " + table + "_updated (updated))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + meta + " (meta_name VARCHAR(32) NOT NULL PRIMARY KEY,"
                    + " meta_value BIGINT NOT NULL)");
        }
        Long stored = readMeta(db, "fingerprint");
        if (stored == null) {
            writeMeta(db, "fingerprint", fingerprint);
        } else if (stored != fingerprint) {
            throw new SQLException("the database was created with a different secret.key; copy the same secret.key"
                    + " to every proxy that shares this database");
        }
        Long cleared = readMeta(db, "cleared");
        clearedSeen = cleared == null ? 0 : cleared;
        long now = clock.getAsLong();
        try (PreparedStatement select = db.prepareStatement("SELECT vkey, expiry, transfer_until FROM " + table
                + " WHERE expiry > ?")) {
            select.setLong(1, now);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    apply(rows.getLong(1), rows.getLong(2), rows.getLong(3), now);
                }
            }
        }
        lastSync = now;
    }

    @Override
    public boolean shared() {
        return true;
    }

    @Override
    public boolean isVerified(long key, long nowMillis) {
        LongLongMap stripe = stripeOf(key);
        synchronized (stripe) {
            long expiry = stripe.get(key);
            return expiry != 0 && expiry > nowMillis;
        }
    }

    @Override
    public void markVerified(long key, long expiresAtMillis) {
        LongLongMap stripe = stripeOf(key);
        synchronized (stripe) {
            stripe.put(key, expiresAtMillis);
        }
        write(key, expiresAtMillis, 0);
    }

    @Override
    public void expectTransfer(long key, long untilMillis) {
        long expiry;
        LongLongMap stripe = stripeOf(key);
        synchronized (stripe) {
            expiry = stripe.get(key);
        }
        if (expiry != 0) {
            write(key, expiry, untilMillis);
        }
    }

    @Override
    public CompletableFuture<Remote> lookup(long key) {
        CompletableFuture<Remote> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    Connection db = connection();
                    try (PreparedStatement select = db.prepareStatement("SELECT expiry, transfer_until FROM " + table
                            + " WHERE vkey = ?")) {
                        select.setLong(1, key);
                        try (ResultSet rows = select.executeQuery()) {
                            if (!rows.next()) {
                                result.complete(null);
                                return;
                            }
                            Remote remote = new Remote(rows.getLong(1), rows.getLong(2));
                            apply(key, remote.expiry(), remote.transferUntil(), clock.getAsLong());
                            result.complete(remote);
                        }
                    }
                } catch (SQLException | RuntimeException failure) {
                    dropConnection();
                    result.completeExceptionally(failure);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            result.complete(null);
        }
        return result;
    }

    public boolean transferExpected(long key, long nowMillis) {
        synchronized (transfers) {
            return transfers.get(key) > nowMillis;
        }
    }

    @Override
    public int size() {
        int total = 0;
        for (LongLongMap stripe : stripes) {
            synchronized (stripe) {
                total += stripe.size();
            }
        }
        return total;
    }

    @Override
    public int clear() {
        int removed = clearLocal();
        long now = clock.getAsLong();
        submit(() -> {
            Connection db = connection();
            try (Statement statement = db.createStatement()) {
                statement.executeUpdate("DELETE FROM " + table);
            }
            writeMeta(db, "cleared", now);
            clearedSeen = now;
            clearLocal();
        });
        return removed;
    }

    private int clearLocal() {
        int removed = 0;
        for (LongLongMap stripe : stripes) {
            synchronized (stripe) {
                removed += stripe.retain((key, expiry) -> false);
            }
        }
        return removed;
    }

    @Override
    public boolean remove(long key) {
        LongLongMap stripe = stripeOf(key);
        boolean existed;
        synchronized (stripe) {
            existed = stripe.get(key) > clock.getAsLong();
            stripe.remove(key);
        }
        write(key, 0, 0);
        return existed;
    }

    @Override
    public void flush() {
    }

    private void drain() {
        try {
            executor.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    public void sync() throws SQLException {
        Connection db = connection();
        long now = clock.getAsLong();
        Long cleared = readMeta(db, "cleared");
        if (cleared != null && cleared > clearedSeen) {
            clearedSeen = cleared;
            clearLocal();
        }
        try (PreparedStatement select = db.prepareStatement("SELECT vkey, expiry, transfer_until FROM " + table
                + " WHERE updated >= ?")) {
            select.setLong(1, lastSync - SYNC_MARGIN_MILLIS);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    apply(rows.getLong(1), rows.getLong(2), rows.getLong(3), now);
                }
            }
        }
        lastSync = now;
        synchronized (transfers) {
            transfers.retain((key, until) -> until > now);
        }
    }

    public void purge() throws SQLException {
        try (PreparedStatement delete = connection().prepareStatement("DELETE FROM " + table + " WHERE expiry < ?")) {
            delete.setLong(1, clock.getAsLong() - PURGE_AFTER_MILLIS);
            delete.executeUpdate();
        }
    }

    @Override
    public void close() {
        drain();
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        dropConnection();
    }

    private void apply(long key, long expiry, long transferUntil, long now) {
        LongLongMap stripe = stripeOf(key);
        synchronized (stripe) {
            if (expiry > now) {
                stripe.put(key, expiry);
            } else {
                stripe.remove(key);
            }
        }
        if (transferUntil > now) {
            synchronized (transfers) {
                transfers.put(key, transferUntil);
            }
        }
    }

    private void write(long key, long expiry, long transferUntil) {
        long updated = clock.getAsLong();
        submit(() -> {
            try (PreparedStatement upsert = connection().prepareStatement("INSERT INTO " + table
                    + " (vkey, expiry, transfer_until, updated) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE"
                    + " expiry = VALUES(expiry), transfer_until = VALUES(transfer_until), updated = VALUES(updated)")) {
                upsert.setLong(1, key);
                upsert.setLong(2, expiry);
                upsert.setLong(3, transferUntil);
                upsert.setLong(4, updated);
                upsert.executeUpdate();
            }
        });
    }

    private interface Task {
        void run() throws SQLException;
    }

    private void submit(Task task) {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            report("database writes are backing up, dropping one");
            return;
        }
        try {
            executor.execute(() -> {
                pending.decrementAndGet();
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        task.run();
                        return;
                    } catch (SQLException | RuntimeException failure) {
                        dropConnection();
                        if (attempt == 1) {
                            report("database write failed: " + failure.getMessage());
                        }
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            pending.decrementAndGet();
        }
    }

    private void syncQuietly() {
        try {
            sync();
        } catch (SQLException | RuntimeException failure) {
            dropConnection();
            report("database sync failed: " + failure.getMessage());
        }
    }

    private void purgeQuietly() {
        try {
            purge();
        } catch (SQLException | RuntimeException failure) {
            dropConnection();
            report("database cleanup failed: " + failure.getMessage());
        }
    }

    private void report(String message) {
        long now = clock.getAsLong();
        long last = lastErrorLog.get();
        if (now - last >= ERROR_LOG_INTERVAL_MILLIS && lastErrorLog.compareAndSet(last, now)) {
            log.accept(message + " (verification keeps working from the local copy)");
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = factory.open();
            connection.setAutoCommit(true);
        }
        return connection;
    }

    private void dropConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }

    private Long readMeta(Connection db, String name) throws SQLException {
        try (PreparedStatement select = db.prepareStatement("SELECT meta_value FROM " + meta + " WHERE meta_name = ?")) {
            select.setString(1, name);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private void writeMeta(Connection db, String name, long value) throws SQLException {
        try (PreparedStatement upsert = db.prepareStatement("INSERT INTO " + meta + " (meta_name, meta_value) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)")) {
            upsert.setString(1, name);
            upsert.setLong(2, value);
            upsert.executeUpdate();
        }
    }

    private LongLongMap stripeOf(long key) {
        return stripes[(int) (key ^ (key >>> 32)) & (STRIPES - 1)];
    }
}
