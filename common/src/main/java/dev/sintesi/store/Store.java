package dev.sintesi.store;

public interface Store extends AutoCloseable {

    record Remote(long expiry, long transferUntil) {
    }

    boolean isVerified(long key, long nowMillis);

    void markVerified(long key, long expiresAtMillis);

    int size();

    int clear();

    boolean remove(long key);

    void flush();

    default boolean shared() {
        return false;
    }

    default void expectTransfer(long key, long untilMillis) {
    }

    default java.util.concurrent.CompletableFuture<Remote> lookup(long key) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    @Override
    void close();
}
