package dev.sintesi.net;

import dev.sintesi.attack.AttackMonitor;
import dev.sintesi.config.SintesiConfig;
import dev.sintesi.limbo.LimboTemplates;
import dev.sintesi.protocol.HandshakeValidator;
import dev.sintesi.protocol.PacketIds;
import dev.sintesi.stats.Statistics;
import dev.sintesi.store.Store;
import dev.sintesi.text.Messages;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class SintesiCore {

    private static final int HANDSHAKE_FRAME_LIMIT = 1024;
    private static final long TRANSFER_WINDOW_MILLIS = 30_000;
    private static final int MAX_PENDING_TRANSFERS = 1 << 16;

    private final SintesiConfig config;
    private final Store store;
    private final LongSupplier clock;
    private final PacketIds ids = PacketIds.load();
    private final LimboTemplates templates = LimboTemplates.load();
    private final Blocklist blocklist;
    private final ConnectionGate gate;
    private final FloodGuard flood;
    private final dev.sintesi.protocol.StatusCache statusCache = new dev.sintesi.protocol.StatusCache();
    private final FailureTracker failures;
    private final AttackMonitor monitor;
    private final HandshakeValidator validator;
    private final EarlyFilterHandler earlyFilter;
    private final Messages messages;
    private final dev.sintesi.store.VerifiedKey keys;
    private final Statistics statistics;
    private final LongAdder verified = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final ConcurrentHashMap<Long, Long> pendingTransfers = new ConcurrentHashMap<>();
    private final Set<InetAddress> trustedGeyser;
    private final ConcurrentHashMap<Long, Integer> sessionsByAddress = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean proxyWarned = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean forwardWarned = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile VpnGuard vpnGuard = new VpnGuard();
    private volatile ClusterTracker clusters;
    private volatile LoginQueue loginQueue;
    private volatile java.util.function.ToIntFunction<InetAddress> onlineCount = address -> 0;
    private final TokenBucketTable remoteLookups = new TokenBucketTable(1, 50, 50);
    private volatile String lastFailure;
    private volatile Consumer<String> logger = message -> {
    };

    public SintesiCore(SintesiConfig config, Store store, LongSupplier clock) {
        this(config, Messages.defaults(), store, clock, new Blocklist(clock), new Statistics(clock.getAsLong()),
                new AttackMonitor(config.attack(), clock), dev.sintesi.store.VerifiedKey.random());
    }

    public SintesiCore(SintesiConfig config, Messages messages, Store store, LongSupplier clock, Blocklist blocklist,
            Statistics statistics, AttackMonitor monitor, dev.sintesi.store.VerifiedKey keys) {
        this.config = config;
        this.keys = keys;
        this.messages = messages;
        this.store = store;
        this.clock = clock;
        this.blocklist = blocklist;
        this.statistics = statistics;
        this.monitor = monitor;
        this.gate = new ConnectionGate(config.gate(), config.floodBlock(),
                () -> monitor.state() != dev.sintesi.api.AttackState.NORMAL, blocklist, clock);
        this.loginQueue = new LoginQueue(config.gate().queueSize(), config.gate().queueMillis(), clock);
        this.clusters = new ClusterTracker(config.clusters(), clock, message -> logger.accept(message));
        this.flood = new FloodGuard(config.floodBlock(), blocklist, clock, message -> logger.accept(message),
                config.logAddresses());
        this.failures = new FailureTracker(blocklist, clock, config.blacklistThreshold(), config.blacklistMillis());
        this.validator = new HandshakeValidator(config.minProtocol(), config.maxProtocol(),
                Set.copyOf(config.requiredHostnames()));
        this.earlyFilter = new EarlyFilterHandler(gate, monitor, statistics, flood);
        Set<InetAddress> geyser = new java.util.HashSet<>();
        for (String address : config.trustedGeyserAddresses()) {
            InetAddress parsed = NumericAddress.parse(address);
            if (parsed == null) {
                throw new IllegalArgumentException("invalid Geyser address " + address);
            }
            geyser.add(parsed);
        }
        this.trustedGeyser = Set.copyOf(geyser);
    }

    public boolean isBedrock(Channel channel) {
        if (config.exemptLocalConnections() && (channel instanceof LocalChannel
                || channel.remoteAddress() instanceof LocalAddress)) {
            return true;
        }
        if (channel.remoteAddress() instanceof InetSocketAddress inet && inet.getAddress() != null) {
            if (config.exemptLocalConnections() && inet.getPort() == 0) {
                return true;
            }
            return trustedGeyser.contains(inet.getAddress());
        }
        return false;
    }

    boolean openSession(long addressKey) {
        if (addressKey == 0L) {
            return true;
        }
        boolean[] opened = {false};
        sessionsByAddress.compute(addressKey, (key, count) -> {
            int current = count == null ? 0 : count;
            if (current >= config.maxSessionsPerAddress()) {
                return count;
            }
            opened[0] = true;
            return current + 1;
        });
        return opened[0];
    }

    void closeSession(long addressKey) {
        if (addressKey != 0L) {
            sessionsByAddress.computeIfPresent(addressKey, (key, count) -> count <= 1 ? null : count - 1);
        }
    }

    public long verifiedKey(String name, long subnetKey) {
        return keys.of(name, subnetKey);
    }

    public Statistics statistics() {
        return statistics;
    }

    public void install(Channel channel) {
        if (isBedrock(channel)) {
            statistics.bedrock();
            return;
        }
        String proxyDecoder = ProxyHeaderHandler.decoderName(channel.pipeline());
        if (proxyDecoder == null) {
            channel.pipeline().addFirst(EarlyFilterHandler.NAME, earlyFilter);
        } else {
            if (!trustsProxy(channel)) {
                rejectedByPlatform();
                channel.pipeline().addFirst(UntrustedProxyHandler.NAME, UntrustedProxyHandler.INSTANCE);
                return;
            }
            channel.pipeline().addAfter(proxyDecoder, ProxyHeaderHandler.NAME, new ProxyHeaderHandler());
            channel.pipeline().addAfter(ProxyHeaderHandler.NAME, EarlyFilterHandler.NAME, earlyFilter);
        }
        channel.pipeline().addAfter(EarlyFilterHandler.NAME, FrameDecoder.NAME, new FrameDecoder(HANDSHAKE_FRAME_LIMIT));
        channel.pipeline().addAfter(FrameDecoder.NAME, ConnectionHandler.NAME, new ConnectionHandler(this));
        moveReadTimeoutsFirst(channel.pipeline());
    }

    public void onlineCount(java.util.function.ToIntFunction<InetAddress> counter) {
        this.onlineCount = counter;
    }

    boolean tooManyOnline(Channel channel) {
        int limit = config.maxOnlinePerAddress();
        return limit > 0 && ClientAddress.of(channel) instanceof InetSocketAddress inet && inet.getAddress() != null
                && !ClientAddress.isLocalProxy(channel) && onlineCount.applyAsInt(inet.getAddress()) >= limit;
    }

    public void loginQueue(LoginQueue queue) {
        this.loginQueue = queue;
    }

    public LoginQueue loginQueue() {
        return loginQueue;
    }

    public void clusters(ClusterTracker tracker) {
        this.clusters = tracker;
    }

    public ClusterTracker clusters() {
        return clusters;
    }

    public void vpnGuard(VpnGuard guard) {
        this.vpnGuard = guard;
    }

    boolean vpnBlocked(java.net.SocketAddress remote) {
        dev.sintesi.config.VpnConfig.Mode mode = config.vpn().mode();
        if (mode == dev.sintesi.config.VpnConfig.Mode.NEVER
                || mode == dev.sintesi.config.VpnConfig.Mode.DURING_ATTACK
                        && monitor.state() == dev.sintesi.api.AttackState.NORMAL) {
            return false;
        }
        return remote instanceof InetSocketAddress inet && inet.getAddress() != null
                && vpnGuard.blocks(inet.getAddress());
    }

    void warnForwardedAddress() {
        if (forwardWarned.compareAndSet(false, true)) {
            logger.accept("A client connected with the player address inside the hostname (TCPShield style"
                    + " host///address forwarding). Sintesi only sees the address of the TCPShield edge for these"
                    + " players, so per address limits apply to the edge. Enable the PROXY protocol in TCPShield and"
                    + " on this server and list the TCPShield addresses in proxy-protocol.trusted-addresses.");
        }
    }

    private boolean trustsProxy(Channel channel) {
        ProxyTrust trust = config.trustedProxies();
        if (trust.isEmpty()) {
            if (proxyWarned.compareAndSet(false, true)) {
                logger.accept("The proxy protocol is enabled and proxy-protocol.trusted-addresses is empty: anyone who"
                        + " can reach this port directly can choose the address Sintesi sees. List the addresses of"
                        + " your proxies or load balancers there.");
            }
            return true;
        }
        return channel.remoteAddress() instanceof InetSocketAddress peer && peer.getAddress() != null
                && trust.trusts(peer.getAddress());
    }

    static void moveReadTimeoutsFirst(ChannelPipeline pipeline) {
        List<Map.Entry<String, ChannelHandler>> timeouts = new ArrayList<>();
        for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
            if (entry.getValue().getClass() == ReadTimeoutHandler.class) {
                timeouts.add(entry);
            }
        }
        for (int index = timeouts.size() - 1; index >= 0; index--) {
            Map.Entry<String, ChannelHandler> entry = timeouts.get(index);
            long millis = ((ReadTimeoutHandler) entry.getValue()).getReaderIdleTimeInMillis();
            pipeline.remove(entry.getValue());
            pipeline.addFirst(entry.getKey(), new ReadTimeoutHandler(millis, TimeUnit.MILLISECONDS));
        }
    }

    public void rejectedByPlatform() {
        statistics.connection();
        monitor.recordConnection();
        statistics.blocked();
    }

    public SintesiConfig config() {
        return config;
    }

    public Store store() {
        return store;
    }

    public LongSupplier clock() {
        return clock;
    }

    public PacketIds ids() {
        return ids;
    }

    public LimboTemplates templates() {
        return templates;
    }

    public Blocklist blocklist() {
        return blocklist;
    }

    public dev.sintesi.protocol.StatusCache statusCache() {
        return statusCache;
    }

    FloodGuard flood() {
        return flood;
    }

    public ConnectionGate gate() {
        return gate;
    }

    public AttackMonitor monitor() {
        return monitor;
    }

    public HandshakeValidator validator() {
        return validator;
    }

    public Messages messages() {
        return messages;
    }

    public long verifiedCount() {
        return verified.sum();
    }

    public long rejectedCount() {
        return rejected.sum();
    }

    void countVerified() {
        verified.increment();
    }

    void countRejected() {
        rejected.increment();
    }

    void countRejected(String reason) {
        lastFailure = reason;
        rejected.increment();
    }

    boolean recordFailure(long addressKey) {
        return failures.recordFailure(addressKey);
    }

    void expectTransfer(long key) {
        long until = clock.getAsLong() + TRANSFER_WINDOW_MILLIS;
        if (pendingTransfers.size() < MAX_PENDING_TRANSFERS) {
            pendingTransfers.put(key, until);
        }
        store.expectTransfer(key, until);
    }

    boolean transferExpectedElsewhere(long key) {
        return store instanceof dev.sintesi.store.SqlStore shared && shared.transferExpected(key, clock.getAsLong());
    }

    boolean remoteLookupAllowed() {
        return store.shared() && remoteLookups.tryAcquire(0L, clock.getAsLong());
    }

    boolean hasPendingTransfer(long key) {
        Long expiry = pendingTransfers.get(key);
        return expiry != null && expiry > clock.getAsLong();
    }

    boolean consumeTransfer(long key) {
        Long expiry = pendingTransfers.remove(key);
        return expiry != null && expiry > clock.getAsLong();
    }

    public void purgeExpired() {
        long now = clock.getAsLong();
        blocklist.purgeExpired();
        flood.flushLog();
        pendingTransfers.values().removeIf(expiry -> expiry <= now);
    }

    public void logger(Consumer<String> logger) {
        this.logger = logger;
    }

    void log(String message) {
        if (monitor.state() == dev.sintesi.api.AttackState.NORMAL) {
            logger.accept(message);
        }
    }

    public String lastFailure() {
        return lastFailure;
    }
}
