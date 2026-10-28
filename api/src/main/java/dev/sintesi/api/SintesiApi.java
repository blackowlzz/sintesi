package dev.sintesi.api;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class SintesiApi {

    private static final List<SintesiListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Supplier<AttackState> state = () -> AttackState.NORMAL;
    private static volatile Consumer<String> errors = message -> {
    };

    private SintesiApi() {
    }

    public static void addListener(SintesiListener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(SintesiListener listener) {
        LISTENERS.remove(listener);
    }

    public static AttackState attackState() {
        return state.get();
    }

    public static void bind(Supplier<AttackState> attackState, Consumer<String> errorLog) {
        state = attackState;
        errors = errorLog;
    }

    public static void attackStarted(long cps) {
        fire(listener -> listener.attackStarted(cps));
    }

    public static void attackEnded(long durationMillis, long peak, long blocked, long failed, long passed) {
        fire(listener -> listener.attackEnded(durationMillis, peak, blocked, failed, passed));
    }

    public static void playerVerified(String name, InetAddress address) {
        fire(listener -> listener.playerVerified(name, address));
    }

    public static void verificationFailed(String name, InetAddress address) {
        fire(listener -> listener.verificationFailed(name, address));
    }

    public static void addressBlocked(InetAddress address, long minutes) {
        fire(listener -> listener.addressBlocked(address, minutes));
    }

    private static void fire(Consumer<SintesiListener> event) {
        for (SintesiListener listener : LISTENERS) {
            try {
                event.accept(listener);
            } catch (RuntimeException | LinkageError failure) {
                errors.accept("A Sintesi API listener failed: " + failure);
            }
        }
    }
}
