package dev.sintesi.text;

import dev.sintesi.api.AttackState;
import dev.sintesi.platform.SintesiRuntime;
import dev.sintesi.platform.SintesiRuntime.AttackSummary;
import dev.sintesi.stats.Statistics.Snapshot;
import java.text.NumberFormat;
import java.util.Locale;

public final class Placeholders {

    private Placeholders() {
    }

    public static String number(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    public static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long days = seconds / 86_400;
        long hours = seconds / 3600 % 24;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            out.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            out.append(minutes).append("m ");
        }
        return out.append(rest).append('s').toString();
    }

    public static String state(Messages messages, AttackState state) {
        return messages.raw("state." + state.name().toLowerCase(Locale.ROOT));
    }

    public static Object[] live(Messages messages, Snapshot snapshot, AttackState state) {
        return new Object[] {
            "cps", number(snapshot.cps()),
            "joins", number(snapshot.joins()),
            "pings", number(snapshot.pings()),
            "verifying", number(snapshot.verifying()),
            "passed", number(snapshot.passed()),
            "failed", number(snapshot.failed()),
            "blocked", number(snapshot.blocked()),
            "peak", number(snapshot.peakCps()),
            "state", state(messages, state)
        };
    }

    public static Object[] stats(SintesiRuntime runtime) {
        Messages messages = runtime.messages();
        Snapshot snapshot = runtime.statistics().last();
        Runtime jvm = Runtime.getRuntime();
        String lastFailure = runtime.core().lastFailure();
        return concat(live(messages, snapshot, runtime.monitor().state()), new Object[] {
            "uptime", duration(runtime.uptimeMillis()),
            "total_passed", number(snapshot.totalPassed()),
            "total_failed", number(snapshot.totalFailed()),
            "total_blocked", number(snapshot.totalBlocked()),
            "total_connections", number(snapshot.totalConnections()),
            "stored", number(runtime.store().size()),
            "bedrock", number(runtime.statistics().bedrockTotal()),
            "vpn_refused", number(runtime.statistics().vpnRefusedTotal()),
            "blocklisted", number(runtime.blocklist().size()),
            "protocols", number(runtime.core().templates().protocols().size()),
            "last_failure", lastFailure == null ? "none"
                    : runtime.core().config().logFailureReasons() ? lastFailure : "hidden (logging.failure-reasons)",
            "memory_used", number((jvm.totalMemory() - jvm.freeMemory()) >> 20),
            "memory_max", number(jvm.maxMemory() >> 20)
        });
    }

    public static Object[] summary(AttackSummary summary) {
        return new Object[] {
            "duration", duration(summary.durationMillis()),
            "peak", number(summary.peakCps()),
            "total_blocked", number(summary.blocked()),
            "total_failed", number(summary.failed()),
            "total_passed", number(summary.passed())
        };
    }

    private static Object[] concat(Object[] first, Object[] second) {
        Object[] out = new Object[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
