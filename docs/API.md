# Sintesi API

Other plugins can follow what Sintesi does through `dev.sintesi.api.SintesiApi`. On Velocity declare a dependency on the plugin id `sintesi`; on BungeeCord and Paper add `Sintesi` to `depend` or `softdepend` in your plugin descriptor.

```java
SintesiApi.addListener(new SintesiListener() {
    @Override
    public void playerVerified(String name, InetAddress address) {
    }

    @Override
    public void attackEnded(long durationMillis, long peakConnectionsPerSecond, long blockedConnections,
            long failedVerifications, long passedVerifications) {
    }
});
```

| Method | When |
|---|---|
| `attackStarted(connectionsPerSecond)` | The attack state reaches attack. |
| `attackEnded(duration, peak, blocked, failed, passed)` | The attack state goes back to normal. |
| `playerVerified(name, address)` | A player passed verification. |
| `verificationFailed(name, address)` | A player failed verification. The reason is not given on purpose. |
| `addressBlocked(address, minutes)` | An address was blocked after a connection flood. |

`SintesiApi.attackState()` returns the current state (`NORMAL`, `ELEVATED`, `ATTACK`). Remove your listener with `SintesiApi.removeListener` when your plugin is disabled.

Listeners are called on network and maintenance threads, so they must be thread safe and return quickly; do slow work on your own executor. An exception thrown by a listener is logged and does not affect Sintesi.
