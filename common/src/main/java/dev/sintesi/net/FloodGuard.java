package dev.sintesi.net;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class FloodGuard {

    private static final int NOTICE_SIZE_LOG2 = 16;
    private static final int NOTICE_MASK = (1 << NOTICE_SIZE_LOG2) - 1;
    private static final long TIME_MASK = (1L << 47) - 1;
    private static final long BUSY = 1L << 47;
    private static final int LOG_LINES_PER_SECOND = 5;

    private final FloodBlockConfig config;
    private final Blocklist blocklist;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final boolean showAddresses;
    private final AtomicLongArray notices = new AtomicLongArray(1 << NOTICE_SIZE_LOG2);
    private final AtomicLong logSecond = new AtomicLong();
    private final AtomicInteger loggedThisSecond = new AtomicInteger();
    private final LongAdder suppressed = new LongAdder();
    private final java.util.concurrent.atomic.AtomicBoolean loopbackWarned = new java.util.concurrent.atomic.AtomicBoolean();

    public FloodGuard(FloodBlockConfig config, Blocklist blocklist, LongSupplier clock, Consumer<String> log) {
        this(config, blocklist, clock, log, true);
    }

    public FloodGuard(FloodBlockConfig config, Blocklist blocklist, LongSupplier clock, Consumer<String> log,
            boolean showAddresses) {
        this.showAddresses = showAddresses;
        this.config = config;
        this.blocklist = blocklist;
        this.clock = clock;
        this.log = log;
    }

    public static boolean exempt(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            return raw[0] == 127 && raw[1] == 0 && raw[2] == 0 && raw[3] == 1;
        }
        for (int i = 0; i < 15; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[15] == 1;
    }

    public void warnLoopback() {
        if (loopbackWarned.compareAndSet(false, true)) {
            log.accept("Connections from 127.0.0.1 exceeded the flood threshold. They are never blocked, because"
                    + " they usually come from a proxy or relay on this machine. If a proxy runs in front of this"
                    + " server, install Sintesi on the proxy instead: here every player shares the proxy address.");
        }
    }

    public void block(long addressKey, InetAddress address) {
        Blocklist.Entry entry = blocklist.blockNew(addressKey, config.blockMillis(), Blocklist.Reason.FLOOD);
        if (entry != null) {
            dev.sintesi.api.SintesiApi.addressBlocked(address, config.blockMillis() / 60_000);
            report((showAddresses ? address.getHostAddress() : "An address") + " blocked for " + config.blockMillis() / 60_000
                    + " minutes after a connection flood (code #SINTESI-" + entry.code() + ", lift it with /sintesi"
                    + " blocklist remove " + entry.code() + ")");
        }
    }

    public boolean mayDeliver(long addressKey) {
        long hash = SubnetKey.mix(addressKey);
        long current = notices.get((int) hash & NOTICE_MASK);
        if ((current >>> 48) != ((hash >>> 48) & 0xFFFF)) {
            return true;
        }
        long sent = current & TIME_MASK;
        return sent == 0 || ((clock.getAsLong() - sent) & TIME_MASK) >= config.noticeIntervalMillis();
    }

    public Blocklist.Entry acquireNotice(long addressKey) {
        Blocklist.Entry entry = blocklist.entry(addressKey);
        if (entry == null || entry.reason() != Blocklist.Reason.FLOOD) {
            return null;
        }
        long hash = SubnetKey.mix(addressKey);
        int index = (int) hash & NOTICE_MASK;
        long tag = (hash >>> 48) & 0xFFFF;
        long now = clock.getAsLong() & TIME_MASK;
        while (true) {
            long current = notices.get(index);
            long sent = 0;
            if (current != 0 && (current >>> 48) == tag) {
                if ((current & BUSY) != 0) {
                    return null;
                }
                sent = current & TIME_MASK;
                if (!config.allowVerifiedPlayers() && sent != 0 && ((now - sent) & TIME_MASK) < config.noticeIntervalMillis()) {
                    return null;
                }
            }
            if (notices.compareAndSet(index, current, (tag << 48) | BUSY | sent)) {
                return entry;
            }
        }
    }

    public void releaseNotice(long addressKey, boolean delivered) {
        long hash = SubnetKey.mix(addressKey);
        int index = (int) hash & NOTICE_MASK;
        long tag = (hash >>> 48) & 0xFFFF;
        long now = Math.max(1, clock.getAsLong() & TIME_MASK);
        while (true) {
            long current = notices.get(index);
            if ((current >>> 48) != tag || (current & BUSY) == 0) {
                return;
            }
            long next = (tag << 48) | (delivered ? now : current & TIME_MASK);
            if (notices.compareAndSet(index, current, next)) {
                return;
            }
        }
    }

    public void flushLog() {
        long hidden = suppressed.sumThenReset();
        if (hidden > 0) {
            log.accept(hidden + " more addresses blocked after a connection flood");
        }
    }

    private void report(String line) {
        long second = clock.getAsLong() / 1000;
        long previous = logSecond.get();
        if (previous != second && logSecond.compareAndSet(previous, second)) {
            loggedThisSecond.set(0);
        }
        if (loggedThisSecond.incrementAndGet() <= LOG_LINES_PER_SECOND) {
            log.accept(line);
        } else {
            suppressed.increment();
        }
    }
}
