package dev.sintesi.api;

import java.net.InetAddress;

public interface SintesiListener {

    default void attackStarted(long connectionsPerSecond) {
    }

    default void attackEnded(long durationMillis, long peakConnectionsPerSecond, long blockedConnections,
            long failedVerifications, long passedVerifications) {
    }

    default void playerVerified(String name, InetAddress address) {
    }

    default void verificationFailed(String name, InetAddress address) {
    }

    default void addressBlocked(InetAddress address, long minutes) {
    }
}
