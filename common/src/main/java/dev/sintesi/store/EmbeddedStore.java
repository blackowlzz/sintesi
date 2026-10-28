package dev.sintesi.store;

import dev.sintesi.util.LongLongMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.LongSupplier;

public final class EmbeddedStore implements Store {

    private static final int STRIPES = 64;
    private static final int MAGIC = 0x53505632;
    private static final int HEADER_BYTES = 12;
    private static final int RECORD_BYTES = 16;

    private final Path file;
    private final LongSupplier clock;
    private final long fingerprint;
    private final LongLongMap[] stripes = new LongLongMap[STRIPES];
    private final Object writeLock = new Object();
    private DataOutputStream out;

    public EmbeddedStore(Path file, LongSupplier clock) {
        this(file, clock, 0L);
    }

    public EmbeddedStore(Path file, LongSupplier clock, long fingerprint) {
        this.file = file;
        this.clock = clock;
        this.fingerprint = fingerprint;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new LongLongMap();
        }
        load();
        openWriter();
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
        append(key, expiresAtMillis);
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
    public void flush() {
        synchronized (writeLock) {
            try {
                out.flush();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    @Override
    public boolean remove(long key) {
        LongLongMap stripe = stripeOf(key);
        boolean existed;
        synchronized (stripe) {
            existed = stripe.get(key) > clock.getAsLong();
            stripe.remove(key);
        }
        append(key, 0L);
        return existed;
    }

    @Override
    public int clear() {
        int removed = 0;
        for (LongLongMap stripe : stripes) {
            synchronized (stripe) {
                removed += stripe.retain((key, expiry) -> false);
            }
        }
        compact();
        return removed;
    }

    public void compact() {
        synchronized (writeLock) {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            long now = clock.getAsLong();
            try {
                Files.deleteIfExists(temp);
                createPrivate(temp);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            try (DataOutputStream writer = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
                writer.writeInt(MAGIC);
                writer.writeLong(fingerprint);
                for (LongLongMap stripe : stripes) {
                    synchronized (stripe) {
                        stripe.retain((key, expiry) -> expiry > now);
                        stripe.forEach((key, expiry) -> {
                            try {
                                writer.writeLong(key);
                                writer.writeLong(expiry);
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        });
                    }
                }
                writer.flush();
                out.close();
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                openWriter();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    @Override
    public void close() {
        synchronized (writeLock) {
            try {
                out.close();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private LongLongMap stripeOf(long key) {
        return stripes[(int) (key >>> 58) & (STRIPES - 1)];
    }

    private void append(long key, long expiry) {
        synchronized (writeLock) {
            try {
                out.writeLong(key);
                out.writeLong(expiry);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            long size = Files.size(file);
            if (size == 0) {
                return;
            }
            if (size < HEADER_BYTES || !headerMatches()) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            long extra = (size - HEADER_BYTES) % RECORD_BYTES;
            if (extra != 0) {
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.truncate(size - extra);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            in.skipNBytes(HEADER_BYTES);
            while (true) {
                long key = in.readLong();
                long expiry = in.readLong();
                LongLongMap stripe = stripeOf(key);
                if (expiry == 0L) {
                    stripe.remove(key);
                } else {
                    stripe.put(key, expiry);
                }
            }
        } catch (EOFException endOfFile) {
            return;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void createPrivate(Path path) throws IOException {
        try {
            Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(path);
        }
    }

    private boolean headerMatches() throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            return in.readInt() == MAGIC && in.readLong() == fingerprint;
        }
    }

    private void openWriter() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            boolean fresh = !Files.exists(file) || Files.size(file) == 0;
            if (!Files.exists(file)) {
                createPrivate(file);
            }
            out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                    file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
            if (fresh) {
                out.writeInt(MAGIC);
                out.writeLong(fingerprint);
                out.flush();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
