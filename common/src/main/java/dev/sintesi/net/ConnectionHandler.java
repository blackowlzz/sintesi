package dev.sintesi.net;

import dev.sintesi.limbo.LimboSession;
import dev.sintesi.protocol.DecodeException;
import dev.sintesi.protocol.Handshake;
import dev.sintesi.protocol.PacketIds.Side;
import dev.sintesi.protocol.PacketIds.State;
import dev.sintesi.protocol.PacketSender;
import dev.sintesi.protocol.ProtocolBuffers;
import dev.sintesi.protocol.packet.DisconnectPackets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.SplittableRandom;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class ConnectionHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "sintesi-connection";

    private static final int LIMBO_FRAME_LIMIT = 8192;
    private static final long PRE_LOGIN_TIMEOUT_SECONDS = 5;
    private static final long ATTACK_PRE_LOGIN_TIMEOUT_SECONDS = 2;
    private static final long LOOKUP_TIMEOUT_SECONDS = 2;
    private static final long STATUS_REFRESH_MILLIS = 5_000;
    private static final long STATUS_MAX_AGE_MILLIS = 60_000;
    private static final int STATUS_REQUEST = 0x00;
    private static final int STATUS_PING = 0x01;

    private enum Stage {
        HANDSHAKE,
        LOGIN,
        LOOKUP,
        QUEUED,
        STATUS,
        LIMBO,
        DONE
    }

    private final SintesiCore core;
    private Stage stage = Stage.HANDSHAKE;
    private byte[] handshakeFrame;
    private Handshake handshake;
    private ScheduledFuture<?> preLoginTimeout;
    private LimboSession session;
    private boolean verifying;
    private String who;
    private long sessionAddress;
    private boolean sessionOpen;
    private boolean limboSlot;
    private boolean noticeDelivered;
    private byte[] cachedStatus;

    public ConnectionHandler(SintesiCore core) {
        this.core = core;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        long seconds = core.monitor().state() == dev.sintesi.api.AttackState.NORMAL
                ? PRE_LOGIN_TIMEOUT_SECONDS : ATTACK_PRE_LOGIN_TIMEOUT_SECONDS;
        preLoginTimeout = ctx.executor().schedule(() -> {
            ctx.close();
        }, seconds, TimeUnit.SECONDS);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseNotice(ctx);
        cancelTimeout();
        closeSession();
    }

    private void closeSession() {
        if (session != null) {
            session.cancel();
        }
        if (verifying) {
            verifying = false;
            core.statistics().verificationAbandoned();
            core.log(who + " disconnected during verification"
                    + (core.config().logFailureReasons() ? " (" + session.describeProgress() + ")" : ""));
        }
        releaseAddress();
    }

    private void releaseAddress() {
        if (sessionOpen) {
            sessionOpen = false;
            core.closeSession(sessionAddress);
        }
        if (limboSlot) {
            limboSlot = false;
            core.gate().releaseLimbo();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseNotice(ctx);
        cancelTimeout();
        closeSession();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!(message instanceof ByteBuf frame)) {
            ctx.fireChannelRead(message);
            return;
        }
        try {
            switch (stage) {
                case HANDSHAKE -> onHandshake(ctx, frame);
                case LOGIN -> onLogin(ctx, frame);
                case STATUS -> onStatus(ctx, frame);
                case LOOKUP, QUEUED -> reject(ctx);
                case LIMBO -> session.onFrame(ctx, frame);
                default -> {
                }
            }
        } catch (DecodeException | IndexOutOfBoundsException malformed) {
            reject(ctx);
        } finally {
            frame.release();
        }
    }

    private void onHandshake(ChannelHandlerContext ctx, ByteBuf frame) {
        byte[] raw = ByteBufUtil.getBytes(frame, frame.readerIndex(), frame.readableBytes());
        Handshake parsed = Handshake.read(frame);
        if (!core.validator().accepts(parsed)) {
            reject(ctx);
            return;
        }
        Blocklist.Entry notice = ctx.channel().attr(EarlyFilterHandler.NOTICE).get();
        if (notice != null) {
            if (parsed.nextState() == Handshake.STATUS) {
                reject(ctx);
                return;
            }
            if (!core.config().floodBlock().allowVerifiedPlayers()) {
                kickBlocked(ctx, parsed.protocol(), notice);
                return;
            }
        }
        if (parsed.forwardsAddress()) {
            core.warnForwardedAddress();
        }
        handshake = parsed;
        handshakeFrame = raw;
        if (parsed.nextState() == Handshake.STATUS) {
            onStatusHandshake(ctx, parsed);
            return;
        }
        stage = Stage.LOGIN;
    }

    private void onStatusHandshake(ChannelHandlerContext ctx, Handshake parsed) {
        boolean caching = core.config().gate().statusCache();
        dev.sintesi.protocol.StatusCache.Entry cached = caching
                ? core.statusCache().get(parsed.protocol(), parsed.host()) : null;
        long age = cached == null ? Long.MAX_VALUE : core.clock().getAsLong() - cached.capturedAt();
        boolean pressure = core.monitor().state() != dev.sintesi.api.AttackState.NORMAL;
        if (cached != null && pressure && age < STATUS_REFRESH_MILLIS) {
            answerFromCache(ctx, cached);
            return;
        }
        if (core.gate().admitStatus() == Admission.ACCEPT) {
            core.statistics().ping();
            if (caching && age >= STATUS_REFRESH_MILLIS) {
                ctx.pipeline().addFirst(StatusCaptureHandler.NAME, new StatusCaptureHandler(core.statusCache(),
                        parsed.protocol(), parsed.host(), core.clock()));
            }
            handOff(ctx, handshakeFrame);
            return;
        }
        if (cached != null && age < STATUS_MAX_AGE_MILLIS) {
            answerFromCache(ctx, cached);
            return;
        }
        reject(ctx);
    }

    private void answerFromCache(ChannelHandlerContext ctx, dev.sintesi.protocol.StatusCache.Entry cached) {
        if (core.gate().admitCachedStatus() != Admission.ACCEPT) {
            reject(ctx);
            return;
        }
        core.statistics().ping();
        cachedStatus = cached.frame();
        stage = Stage.STATUS;
    }

    private void onStatus(ChannelHandlerContext ctx, ByteBuf frame) {
        int id = ProtocolBuffers.readVarInt(frame);
        if (id == STATUS_REQUEST && cachedStatus != null && !frame.isReadable()) {
            ctx.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(cachedStatus));
            cachedStatus = null;
            return;
        }
        if (id == STATUS_PING && cachedStatus == null && frame.readableBytes() == 8) {
            stage = Stage.DONE;
            byte[] payload = ByteBufUtil.getBytes(frame);
            ctx.writeAndFlush(io.netty.buffer.Unpooled.wrappedBuffer(dev.sintesi.protocol.StatusCache.pong(payload)))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        reject(ctx);
    }

    private void onLogin(ChannelHandlerContext ctx, ByteBuf frame) {
        byte[] raw = ByteBufUtil.getBytes(frame, frame.readerIndex(), frame.readableBytes());
        int protocol = handshake.protocol();
        int id = ProtocolBuffers.readVarInt(frame);
        if (id != core.ids().id(State.LOGIN, Side.TO_SERVER, "LOGIN_START", protocol)) {
            reject(ctx);
            return;
        }
        String name = ProtocolBuffers.readString(frame, 16);
        if (!core.config().namePattern().matcher(name).matches()) {
            reject(ctx);
            return;
        }
        core.statistics().join();
        long addressKey = SubnetKey.address(ClientAddress.of(ctx.channel()));
        long key = core.verifiedKey(name, addressKey);
        if (core.config().blockedProtocols().contains(protocol)) {
            kickUnsupported(ctx);
            return;
        }
        if (core.vpnBlocked(ClientAddress.of(ctx.channel()))) {
            kickVpn(ctx, protocol, name);
            return;
        }
        Blocklist.Entry notice = ctx.channel().attr(EarlyFilterHandler.NOTICE).get();
        if (notice != null) {
            if (core.store().isVerified(key, core.clock().getAsLong())) {
                handOffVerified(ctx, key, raw, false);
            } else {
                kickBlocked(ctx, protocol, notice);
            }
            return;
        }
        boolean known = core.store().isVerified(key, core.clock().getAsLong());
        if (!known && core.config().verifyOnlyDuringAttacks()
                && core.monitor().state() == dev.sintesi.api.AttackState.NORMAL) {
            handOffVerified(ctx, key, raw, false);
            return;
        }
        if (handshake.nextState() == Handshake.TRANSFER && !core.hasPendingTransfer(key)
                && !(known && core.transferExpectedElsewhere(key)) && core.remoteLookupAllowed()) {
            lookUpThenContinue(ctx, name, key, addressKey, protocol, raw);
            return;
        }
        continueLogin(ctx, name, key, addressKey, protocol, raw, known, false);
    }

    private void continueLogin(ChannelHandlerContext ctx, String name, long key, long addressKey, int protocol,
            byte[] raw, boolean known, boolean transferredElsewhere) {
        boolean supported = core.templates().supports(protocol);
        if (known || (!supported && core.config().allowUnsupportedProtocols())) {
            handOffVerified(ctx, key, raw, transferredElsewhere);
            return;
        }
        if (!supported) {
            kickUnsupported(ctx);
            return;
        }
        startLimbo(ctx, name, key, addressKey, protocol);
    }

    private void lookUpThenContinue(ChannelHandlerContext ctx, String name, long key, long addressKey, int protocol,
            byte[] raw) {
        stage = Stage.LOOKUP;
        ctx.channel().config().setAutoRead(false);
        core.store().lookup(key).orTimeout(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((remote, failure) ->
                ctx.executor().execute(() -> {
                    if (stage != Stage.LOOKUP || !ctx.channel().isOpen()) {
                        return;
                    }
                    stage = Stage.LOGIN;
                    ctx.channel().config().setAutoRead(true);
                    long now = core.clock().getAsLong();
                    boolean known = remote != null && remote.expiry() > now || core.store().isVerified(key, now);
                    boolean transferred = remote != null && remote.transferUntil() > now;
                    continueLogin(ctx, name, key, addressKey, protocol, raw, known, transferred);
                }));
    }

    private void handOffVerified(ChannelHandlerContext ctx, long key, byte[] loginStart, boolean transferredElsewhere) {
        if (core.tooManyOnline(ctx.channel())) {
            stage = Stage.DONE;
            cancelTimeout();
            int protocol = handshake.protocol();
            PacketSender sender = new PacketSender(core.ids(), protocol);
            ctx.writeAndFlush(sender.frame(ctx.alloc(), State.LOGIN, "DISCONNECT",
                    out -> DisconnectPackets.login(out, protocol, core.messages().tooManyAccounts())))
                    .addListener(ChannelFutureListener.CLOSE);
            core.statistics().blocked();
            return;
        }
        if (handshake.nextState() == Handshake.TRANSFER && (core.consumeTransfer(key)
                || transferredElsewhere || core.transferExpectedElsewhere(key))) {
            handshakeFrame[handshakeFrame.length - 1] = Handshake.LOGIN;
        }
        handOff(ctx, handshakeFrame, loginStart);
    }

    private void startLimbo(ChannelHandlerContext ctx, String name, long key, long addressKey, int protocol) {
        cancelTimeout();
        if (!core.openSession(addressKey)) {
            stage = Stage.DONE;
            PacketSender sender = new PacketSender(core.ids(), protocol);
            ctx.writeAndFlush(sender.frame(ctx.alloc(), State.LOGIN, "DISCONNECT",
                    out -> DisconnectPackets.login(out, protocol, core.messages().busy())))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        sessionOpen = true;
        sessionAddress = addressKey;
        LoginQueue queue = core.loginQueue();
        if (!queue.isEmpty() || core.gate().admitLimbo() != Admission.ACCEPT) {
            stage = Stage.QUEUED;
            boolean waiting = queue.offer(() -> ctx.channel().isOpen(),
                    () -> core.gate().admitQueuedLimbo(),
                    () -> ctx.executor().execute(() -> {
                        if (stage == Stage.QUEUED && ctx.channel().isOpen()) {
                            limboSlot = true;
                            beginLimbo(ctx, name, key, addressKey, protocol);
                        } else {
                            core.gate().releaseLimbo();
                        }
                    }),
                    () -> ctx.executor().execute(() -> {
                        if (stage == Stage.QUEUED && ctx.channel().isOpen()) {
                            releaseAddress();
                            kickOverloaded(ctx);
                        }
                    }));
            if (!waiting) {
                releaseAddress();
                kickOverloaded(ctx);
            }
            return;
        }
        limboSlot = true;
        beginLimbo(ctx, name, key, addressKey, protocol);
    }

    private void beginLimbo(ChannelHandlerContext ctx, String name, long key, long addressKey, int protocol) {
        long startedAt = core.clock().getAsLong();
        who = name + " (" + addressOf(ctx) + ", protocol " + protocol + ")";
        verifying = true;
        core.statistics().verificationStarted();
        stage = Stage.LIMBO;
        ctx.pipeline().get(FrameDecoder.class).maxFrameLength(LIMBO_FRAME_LIMIT);
        LimboSession.Listener listener = new LimboSession.Listener() {
            @Override
            public void passed(String playerName, boolean transferred) {
                verifying = false;
                releaseAddress();
                core.statistics().verificationFinished(true);
                core.store().markVerified(key, core.clock().getAsLong() + core.config().verifiedLifetimeMillis());
                if (transferred) {
                    core.expectTransfer(key);
                }
                core.countVerified();
                dev.sintesi.api.SintesiApi.playerVerified(playerName, inetOf(ctx));
                core.log(who + " passed verification in " + (core.clock().getAsLong() - startedAt) + " ms"
                        + String.format(java.util.Locale.ROOT, ", %s platform, max fall deviation %.2e",
                                session.platformBlock(), session.maxFallDeviation())
                        + (transferred ? ", transferred back" : ""));
            }

            @Override
            public void captchaShown(String playerName, String code) {
                if (core.config().logFailureReasons()) {
                    core.log(who + " was shown captcha " + code);
                }
            }

            @Override
            public void failed(String playerName, String reason) {
                verifying = false;
                releaseAddress();
                core.statistics().verificationFinished(false);
                core.countRejected(reason);
                dev.sintesi.api.SintesiApi.verificationFailed(playerName, inetOf(ctx));
                core.log(who + " failed verification" + (core.config().logFailureReasons() ? ": " + reason : ""));
                if (core.recordFailure(addressKey)) {
                    core.log(who + " blocked for repeated failures");
                }
            }
        };
        session = new LimboSession(core.ids(), protocol, core.templates().forProtocol(protocol), name, listener,
                core.messages(), core.config().verificationTimeoutMillis(), core.config().physicsTolerance(),
                core.clock(), new SplittableRandom(new java.security.SecureRandom().nextLong()),
                core.config().visiblePlatform());
        if (!core.config().protocolChecks()) {
            session.disableProtocolChecks();
        }
        session.brandPattern(core.config().brandPattern());
        if (core.config().captchaOnFallFailure()) {
            session.captchaOnFallFailure();
        }
        if (!core.config().vehicleCheck()) {
            session.disableVehicleCheck();
        }
        session.compression(core.config().compressionThreshold());
        dev.sintesi.config.SintesiConfig.CaptchaMode captchaMode = core.config().captcha();
        ClusterTracker clusters = core.clusters();
        LimboSession current = session;
        current.fingerprintListener(fingerprints -> {
            for (long fingerprint : fingerprints) {
                clusters.record(fingerprint);
            }
        });
        session.captcha(() -> captchaMode == dev.sintesi.config.SintesiConfig.CaptchaMode.ALWAYS
                || (captchaMode == dev.sintesi.config.SintesiConfig.CaptchaMode.DURING_ATTACK
                        && core.monitor().state() == dev.sintesi.api.AttackState.ATTACK)
                || clusters.requiresCaptcha(current.fingerprints()),
                core.config().captchaMillis(), core.config().captchaAttempts());
        if (core.config().transfer()) {
            String host = core.config().transferHost();
            int port = core.config().transferPort();
            session.transferOnPass(host.isEmpty() ? handshake.address() : host, port == 0 ? handshake.port() : port);
        }
        session.start(ctx);
    }

    private static java.net.InetAddress inetOf(ChannelHandlerContext ctx) {
        return ClientAddress.of(ctx.channel()) instanceof java.net.InetSocketAddress inet ? inet.getAddress() : null;
    }

    private String addressOf(ChannelHandlerContext ctx) {
        if (!core.config().logAddresses()) {
            return "address hidden";
        }
        java.net.SocketAddress remote = ClientAddress.of(ctx.channel());
        if (remote instanceof java.net.InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return String.valueOf(remote);
    }

    private void kickUnsupported(ChannelHandlerContext ctx) {
        stage = Stage.DONE;
        PacketSender sender = new PacketSender(core.ids(), handshake.protocol());
        ByteBuf frame = sender.frame(ctx.alloc(), State.LOGIN, "DISCONNECT",
                out -> DisconnectPackets.login(out, handshake.protocol(), core.messages().unsupported()));
        ctx.writeAndFlush(frame).addListener(ChannelFutureListener.CLOSE);
        core.countRejected();
    }

    private void releaseNotice(ChannelHandlerContext ctx) {
        if (ctx.channel().attr(EarlyFilterHandler.NOTICE).getAndSet(null) != null) {
            core.flood().releaseNotice(SubnetKey.address(ClientAddress.of(ctx.channel())), noticeDelivered);
        }
    }

    private void kickBlocked(ChannelHandlerContext ctx, int protocol, Blocklist.Entry entry) {
        if (!core.flood().mayDeliver(SubnetKey.address(ClientAddress.of(ctx.channel())))) {
            reject(ctx);
            return;
        }
        stage = Stage.DONE;
        noticeDelivered = true;
        cancelTimeout();
        long minutes = Math.max(1, (entry.expiry() - core.clock().getAsLong() + 59_999) / 60_000);
        PacketSender sender = new PacketSender(core.ids(), protocol);
        ByteBuf frame = sender.frame(ctx.alloc(), State.LOGIN, "DISCONNECT",
                out -> DisconnectPackets.login(out, protocol, core.messages().floodBlocked(minutes, entry.code())));
        ctx.writeAndFlush(frame).addListener(ChannelFutureListener.CLOSE);
        core.statistics().blocked();
        core.countRejected();
    }

    private void kickVpn(ChannelHandlerContext ctx, int protocol, String name) {
        stage = Stage.DONE;
        cancelTimeout();
        PacketSender sender = new PacketSender(core.ids(), protocol);
        ctx.writeAndFlush(sender.frame(ctx.alloc(), State.LOGIN, "DISCONNECT",
                out -> DisconnectPackets.login(out, protocol, core.messages().vpnBlocked())))
                .addListener(ChannelFutureListener.CLOSE);
        core.statistics().blocked();
        core.countRejected();
        core.statistics().vpnRefused();
        core.log(name + " (" + addressOf(ctx) + ") refused: VPN, proxy or hosting address");
    }

    private void kickOverloaded(ChannelHandlerContext ctx) {
        stage = Stage.DONE;
        PacketSender sender = new PacketSender(core.ids(), handshake.protocol());
        ByteBuf frame = sender.frame(ctx.alloc(), State.LOGIN, "DISCONNECT",
                out -> DisconnectPackets.login(out, handshake.protocol(), core.messages().overloaded()));
        ctx.writeAndFlush(frame).addListener(ChannelFutureListener.CLOSE);
        core.statistics().blocked();
        core.countRejected();
    }

    private void handOff(ChannelHandlerContext ctx, byte[]... payloads) {
        stage = Stage.DONE;
        cancelTimeout();
        ctx.pipeline().remove(EarlyFilterHandler.NAME);
        if (ctx.pipeline().get(ReleaseHandler.NAME) != null) {
            ctx.pipeline().remove(ReleaseHandler.NAME);
        }
        for (byte[] payload : payloads) {
            ctx.fireChannelRead(PacketSender.frameOf(ctx.alloc(), payload));
        }
        io.netty.channel.ChannelPipeline pipeline = ctx.pipeline();
        pipeline.remove(this);
        pipeline.remove(FrameDecoder.NAME);
    }

    private void reject(ChannelHandlerContext ctx) {
        stage = Stage.DONE;
        core.statistics().blocked();
        core.countRejected();
        ctx.channel().config().setAutoRead(false);
        ctx.close();
    }

    private void cancelTimeout() {
        if (preLoginTimeout != null) {
            preLoginTimeout.cancel(false);
            preLoginTimeout = null;
        }
    }
}
