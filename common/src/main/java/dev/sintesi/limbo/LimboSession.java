package dev.sintesi.limbo;

import dev.sintesi.limbo.FallValidator.Result;
import dev.sintesi.protocol.Compression;
import dev.sintesi.protocol.DecodeException;
import dev.sintesi.protocol.PacketIds;
import dev.sintesi.protocol.PacketIds.Side;
import dev.sintesi.protocol.PacketIds.State;
import dev.sintesi.protocol.PacketSender;
import dev.sintesi.protocol.ProtocolBuffers;
import dev.sintesi.protocol.Versions;
import dev.sintesi.protocol.packet.DisconnectPackets;
import dev.sintesi.protocol.packet.LoginPackets;
import dev.sintesi.protocol.packet.PlayPackets;
import dev.sintesi.text.Messages;
import dev.sintesi.text.Text;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class LimboSession {

    public interface Listener {
        void passed(String name, boolean transferred);

        default void captchaShown(String name, String code) {
        }

        void failed(String name, String reason);
    }

    private enum Probe {
        PENDING,
        WAIT,
        PING,
        SLOT,
        SWING,
        CAPTCHA,
        VEHICLE,
        DISMOUNT,
        DONE
    }

    private enum Phase {
        LOGIN_ACK,
        CONFIGURATION,
        PLAY,
        DONE
    }

    private static final int PACKET_BUDGET_BASE = 300;
    private static final int PACKET_BUDGET_PER_TICK = 6;
    private static final double POSITION_ECHO_TOLERANCE = 0.001;
    public static final int PLATFORM_RADIUS = 3;
    private static final int TIMER_SLACK_TICKS = 20;
    private static final long FIRST_KEEP_ALIVE_DELAY_MILLIS = 400;
    private static final int FAILURE_DELAY_MIN_MILLIS = 1000;
    private static final int FAILURE_DELAY_SPREAD_MILLIS = 2000;
    private static final long PROBE_STEP_MILLIS = 8000;
    private static final long KEEP_ALIVE_TIMEOUT_MILLIS = 10000;
    private static final int TICK_MILLIS = 50;
    private static final int MAX_INFLATED_PACKET = 32768;
    private static final int MAX_BRAND_BYTES = 64;
    private static final int MIN_VIEW_DISTANCE = 2;

    private static final int ORDER_PACKETS = 8;
    private static final long PROBE_PAUSE_MIN_MILLIS = 50;
    private static final long PROBE_PAUSE_MAX_MILLIS = 400;
    private static final java.util.Set<String> ORDER_IGNORED = java.util.Set.of("PLAYER_POSITION",
            "PLAYER_POSITION_AND_ROTATION", "PLAYER_ROTATION", "PLAYER_FLYING", "CLIENT_TICK_END", "KEEP_ALIVE");

    private final PacketIds ids;
    private final PacketSender sender;
    private final int protocol;
    private final LimboTemplate template;
    private final String name;
    private final LimboWorld world;
    private final FallValidator validator;
    private final Listener listener;
    private final Messages messages;
    private long deadline;
    private final long createdAt;
    private final LongSupplier clock;
    private final SplittableRandom random;
    private String transferHost;
    private int transferPort;
    private Phase phase = Phase.LOGIN_ACK;
    private int packets;
    private int teleportId;
    private boolean teleportConfirmed;
    private boolean landed;
    private boolean brandReceived;
    private int moves;
    private int tickEnds;
    private boolean awaitingTickEnd;
    private boolean teleportResponsePending;
    private long playStartedAt;
    private int movementPackets;
    private Probe probe = Probe.PENDING;
    private int playerEntityId;
    private int expectedPing;
    private int expectedSlot = -1;
    private int currentSlot;
    private long probeStepStartedAt;
    private final java.util.List<Probe> probePlan = new java.util.ArrayList<>(3);
    private int probeIndex;
    private long nextProbeAt;
    private boolean vehicleCheck = true;
    private int compressionThreshold = -1;
    private boolean protocolChecks = true;
    private java.util.function.BooleanSupplier captchaRequired = () -> false;
    private long captchaMillis;
    private int captchaAttemptsAllowed;
    private int captchaAttempts;
    private long captchaDeadline;
    private int mapId;
    private dev.sintesi.captcha.CaptchaImage captcha;
    private static final long CAPTCHA_KEEP_ALIVE_MILLIS = 5000;
    private static final long AFTER_CAPTCHA_MILLIS = 15000;
    private int vehicleId;
    private double boatY;
    private double boatMotion;
    private boolean boatFalling;
    private int boatMoves;
    private int boatStillMoves;
    private int paddles;
    private int vehicleInputs;
    private static final double BOAT_GRAVITY = 0.03999999910593033;
    private static final double BOAT_TOLERANCE = 1.0E-7;
    private static final int BOAT_MOVES_REQUIRED = 5;
    private static final int BOAT_STILL_LIMIT = 3;
    private static final float MAX_VEHICLE_INPUT = 0.98f;
    private boolean settingsReceived;
    private long orderHash = 17;
    private int orderCount;
    private long settingsHash;
    private String brand;
    private long[] fingerprints;
    private java.util.regex.Pattern brandPattern;
    private boolean captchaOnFallFailure;
    private boolean fallForgiven;
    private java.util.function.Consumer<long[]> fingerprintSink = ignored -> {
    };
    private long keepAliveId;
    private long keepAliveSentAt;
    private int keepAlivesSent;
    private int keepAlivesAnswered;
    private long nextKeepAliveAt;
    private ScheduledFuture<?> ticker;

    public LimboSession(PacketIds ids, int protocol, LimboTemplate template, String name, Listener listener,
            Messages messages, long timeoutMillis, double tolerance, LongSupplier clock, SplittableRandom random) {
        this(ids, protocol, template, name, listener, messages, timeoutMillis, tolerance, clock, random, false);
    }

    public LimboSession(PacketIds ids, int protocol, LimboTemplate template, String name, Listener listener,
            Messages messages, long timeoutMillis, double tolerance, LongSupplier clock, SplittableRandom random,
            boolean visiblePlatform) {
        this.ids = ids;
        this.sender = new PacketSender(ids, protocol);
        this.protocol = protocol;
        this.template = template;
        this.name = name;
        this.listener = listener;
        this.messages = messages;
        this.clock = clock;
        this.random = random;
        this.createdAt = clock.getAsLong();
        this.deadline = createdAt + timeoutMillis;
        this.world = LimboWorld.random(random, visiblePlatform);
        this.validator = new FallValidator(world.spawnX(), world.spawnY(), world.spawnZ(), world.groundY(), tolerance,
                protocol < Versions.V1_21_2);
    }

    public String platformBlock() {
        return world.block().name().toLowerCase(java.util.Locale.ROOT);
    }

    public double maxFallDeviation() {
        return validator.maxDeviation();
    }

    public void disableProtocolChecks() {
        protocolChecks = false;
    }

    public void captcha(java.util.function.BooleanSupplier required, long millis, int attempts) {
        this.captchaRequired = required;
        this.captchaMillis = millis;
        this.captchaAttemptsAllowed = attempts;
    }

    public void disableVehicleCheck() {
        vehicleCheck = false;
    }

    public void compression(int threshold) {
        this.compressionThreshold = threshold;
    }

    public void transferOnPass(String host, int port) {
        if (protocol >= Versions.V1_20_5) {
            this.transferHost = host;
            this.transferPort = port;
        }
    }

    public void start(ChannelHandlerContext ctx) {
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        if (compressionThreshold >= 0) {
            write(ctx, State.LOGIN, "SET_COMPRESSION", out -> ProtocolBuffers.writeVarInt(out, compressionThreshold));
            sender.compress(compressionThreshold);
        }
        write(ctx, State.LOGIN, "LOGIN_SUCCESS", out -> LoginPackets.loginSuccess(out, protocol, uuid, name));
        if (protocol < Versions.V1_20_2) {
            enterPlay(ctx);
        }
        ctx.flush();
        ticker = ctx.executor().scheduleAtFixedRate(() -> tick(ctx), TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    public void cancel() {
        phase = Phase.DONE;
        if (ticker != null) {
            ticker.cancel(false);
        }
    }

    public void onFrame(ChannelHandlerContext ctx, ByteBuf frame) {
        if (phase == Phase.DONE) {
            return;
        }
        if (++packets > PACKET_BUDGET_BASE + (clock.getAsLong() - createdAt) / TICK_MILLIS * PACKET_BUDGET_PER_TICK) {
            finish(ctx, false, "packet flood");
            return;
        }
        ByteBuf packet = frame;
        try {
            if (sender.compressing()) {
                packet = Compression.unwrap(frame, sender.threshold(), MAX_INFLATED_PACKET);
            }
            handle(ctx, packet);
        } catch (DecodeException | IndexOutOfBoundsException malformed) {
            finish(ctx, false, "malformed packet");
        } finally {
            if (packet != frame) {
                packet.release();
            }
        }
    }

    private void handle(ChannelHandlerContext ctx, ByteBuf frame) {
        int id = ProtocolBuffers.readVarInt(frame);
        State state = phase == Phase.LOGIN_ACK ? State.LOGIN
                : phase == Phase.CONFIGURATION ? State.CONFIGURATION : State.PLAY;
        String packet = ids.name(state, Side.TO_SERVER, protocol, id);
        if (packet == null) {
            return;
        }
        if (orderCount < ORDER_PACKETS && !ORDER_IGNORED.contains(packet)) {
            orderHash = orderHash * 31 + packet.hashCode();
            orderCount++;
        }
        switch (phase) {
            case LOGIN_ACK -> {
                if (packet.equals("LOGIN_SUCCESS_ACK")) {
                    startConfiguration(ctx);
                }
            }
            case CONFIGURATION -> {
                if (packet.equals("CONFIGURATION_END_ACK")) {
                    enterPlay(ctx);
                    ctx.flush();
                } else {
                    handleClientInfo(ctx, packet, frame);
                }
            }
            case PLAY -> handlePlay(ctx, packet, frame);
            default -> {
            }
        }
    }

    private void handlePlay(ChannelHandlerContext ctx, String packet, ByteBuf body) {
        switch (packet) {
            case "KEEP_ALIVE" -> {
                long answered = protocol >= Versions.V1_12_2 ? body.readLong() : ProtocolBuffers.readVarInt(body);
                long expected = protocol >= Versions.V1_12_2 ? keepAliveId : (int) keepAliveId;
                if (keepAlivesSent == keepAlivesAnswered || answered != expected) {
                    finish(ctx, false, "keepalive mismatch");
                    return;
                }
                keepAlivesAnswered++;
                checkPass(ctx);
            }
            case "TELEPORT_CONFIRM" -> {
                if (teleportConfirmed) {
                    finish(ctx, false, "duplicate teleport confirm");
                    return;
                }
                if (ProtocolBuffers.readVarInt(body) != teleportId) {
                    finish(ctx, false, "teleport id mismatch");
                    return;
                }
                if (protocol >= Versions.V26_3 && !confirmedPositionMatches(body)) {
                    finish(ctx, false, "teleport position mismatch");
                    return;
                }
                teleportConfirmed = true;
                teleportResponsePending = true;
            }
            case "CLIENT_TICK_END" -> {
                tickEnds++;
                awaitingTickEnd = false;
                if (tickEnds > elapsedTicks() + TIMER_SLACK_TICKS) {
                    finish(ctx, false, "too many tick ends");
                    return;
                }
                checkPass(ctx);
            }
            case "PLAYER_POSITION" -> onMovement(ctx, Movement.read(body, true, false));
            case "PLAYER_POSITION_AND_ROTATION" -> onMovement(ctx, Movement.read(body, true, true));
            case "PLAYER_ROTATION" -> onLook(ctx, Movement.read(body, false, true));
            case "PLAYER_FLYING" -> onLook(ctx, Movement.read(body, false, false));
            case "PONG" -> {
                int id = body.readInt();
                if (probe != Probe.PING || id != expectedPing) {
                    finish(ctx, false, "unexpected pong");
                    return;
                }
                nextProbe(ctx);
            }
            case "WINDOW_CONFIRMATION" -> {
                int window = body.readByte();
                int action = body.readShort();
                boolean accepted = body.readBoolean();
                if (probe != Probe.PING || window != 0 || action != expectedPing || !accepted) {
                    finish(ctx, false, "unexpected transaction");
                    return;
                }
                nextProbe(ctx);
            }
            case "HELD_ITEM_CHANGE" -> {
                int slot = body.readShort();
                if (slot < 0 || slot > 8) {
                    finish(ctx, false, "slot out of range");
                    return;
                }
                currentSlot = slot;
                if (probe == Probe.SLOT && slot == expectedSlot) {
                    nextProbe(ctx);
                }
            }
            case "ANIMATION" -> {
                int hand = protocol >= Versions.V1_9 ? ProtocolBuffers.readVarInt(body) : 0;
                if (hand != 0 && hand != 1) {
                    finish(ctx, false, "invalid hand");
                    return;
                }
                if (probe == Probe.SWING && hand == 0) {
                    nextProbe(ctx);
                }
            }
            case "VEHICLE_MOVE" -> {
                double x = body.readDouble();
                double y = body.readDouble();
                double z = body.readDouble();
                float yaw = body.readFloat();
                float pitch = body.readFloat();
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw)
                        || !Float.isFinite(pitch) || Math.abs(pitch) > 90f) {
                    finish(ctx, false, "invalid vehicle move");
                    return;
                }
                if (probe == Probe.DISMOUNT) {
                    return;
                }
                if (probe != Probe.VEHICLE) {
                    finish(ctx, false, "unexpected vehicle move");
                    return;
                }
                onBoatMove(ctx, y);
            }
            case "STEER_BOAT" -> {
                body.readBoolean();
                body.readBoolean();
                if (probe == Probe.DISMOUNT) {
                    return;
                }
                if (probe != Probe.VEHICLE) {
                    finish(ctx, false, "unexpected paddle");
                    return;
                }
                paddles++;
                checkBoat(ctx);
            }
            case "CHAT_MESSAGE" -> {
                String message = ProtocolBuffers.readString(body, 256);
                if (probe != Probe.CAPTCHA) {
                    return;
                }
                if (captcha.matches(message)) {
                    deadline = Math.max(deadline, clock.getAsLong() + AFTER_CAPTCHA_MILLIS);
                    startVehicle(ctx);
                    return;
                }
                if (++captchaAttempts >= captchaAttemptsAllowed) {
                    finish(ctx, false, "captcha");
                    return;
                }
                showCaptcha(ctx);
            }
            case "STEER_VEHICLE" -> {
                float sideways = body.readFloat();
                float forward = body.readFloat();
                if (!(Math.abs(sideways) <= MAX_VEHICLE_INPUT) || !(Math.abs(forward) <= MAX_VEHICLE_INPUT)) {
                    finish(ctx, false, "invalid vehicle input");
                    return;
                }
                if (probe == Probe.VEHICLE) {
                    vehicleInputs++;
                    checkBoat(ctx);
                }
            }
            default -> handleClientInfo(ctx, packet, body);
        }
    }

    private void handleClientInfo(ChannelHandlerContext ctx, String packet, ByteBuf body) {
        if (packet.equals("CLIENT_SETTINGS")) {
            String locale = ProtocolBuffers.readString(body, 16);
            int viewDistance = body.readByte();
            if (viewDistance < MIN_VIEW_DISTANCE) {
                finish(ctx, false, "view distance " + viewDistance);
                return;
            }
            long hash = locale.hashCode() * 31L + viewDistance;
            for (int i = body.readerIndex(); i < body.writerIndex(); i++) {
                hash = hash * 31 + body.getByte(i);
            }
            settingsHash = hash;
            settingsReceived = true;
            fingerprint();
            checkPass(ctx);
        } else if (packet.equals("PLUGIN_MESSAGE")) {
            String channel = ProtocolBuffers.readString(body, 256);
            if (!channel.equals(protocol >= Versions.V1_13 ? "minecraft:brand" : "MC|Brand")) {
                return;
            }
            if (brandReceived) {
                finish(ctx, false, "duplicate brand");
                return;
            }
            if (body.readableBytes() < 2 || body.readableBytes() > MAX_BRAND_BYTES) {
                finish(ctx, false, "brand length " + body.readableBytes());
                return;
            }
            String brand = ProtocolBuffers.readString(body, MAX_BRAND_BYTES);
            if (body.isReadable() || !validBrand(brand)
                    || brandPattern != null && !brandPattern.matcher(brand).matches()) {
                finish(ctx, false, "invalid brand");
                return;
            }
            this.brand = brand;
            brandReceived = true;
            fingerprint();
            checkPass(ctx);
        }
    }

    public void brandPattern(java.util.regex.Pattern pattern) {
        this.brandPattern = pattern;
    }

    public void captchaOnFallFailure() {
        this.captchaOnFallFailure = true;
    }

    public void fingerprintListener(java.util.function.Consumer<long[]> sink) {
        this.fingerprintSink = sink;
    }

    public long[] fingerprints() {
        return fingerprints;
    }

    private void fingerprint() {
        if (fingerprints != null || !brandReceived || !settingsReceived) {
            return;
        }
        long skeleton = dev.sintesi.net.SubnetKey.mix(protocol * 0x9E3779B97F4A7C15L ^ orderHash) | 1;
        long coarse = dev.sintesi.net.SubnetKey.mix(skeleton ^ settingsHash) | 1;
        long full = dev.sintesi.net.SubnetKey.mix(coarse ^ brand.hashCode()) | 1;
        fingerprints = new long[] {skeleton, coarse, full};
        fingerprintSink.accept(fingerprints);
    }

    private void startProbe(ChannelHandlerContext ctx) {
        probePlan.clear();
        probePlan.add(Probe.PING);
        probePlan.add(Probe.SLOT);
        if (protocol < Versions.V26_3) {
            probePlan.add(Probe.SWING);
        }
        for (int i = probePlan.size() - 1; i > 0; i--) {
            java.util.Collections.swap(probePlan, i, random.nextInt(i + 1));
        }
        probeIndex = 0;
        nextProbe(ctx);
    }

    private void nextProbe(ChannelHandlerContext ctx) {
        if (probeIndex >= probePlan.size()) {
            afterProbes(ctx);
            return;
        }
        probe = Probe.WAIT;
        nextProbeAt = clock.getAsLong() + random.nextLong(PROBE_PAUSE_MIN_MILLIS, PROBE_PAUSE_MAX_MILLIS + 1);
    }

    private void runProbe(ChannelHandlerContext ctx, Probe step) {
        switch (step) {
            case PING -> sendPing(ctx);
            case SLOT -> askForSlot(ctx);
            case SWING -> askForSwing(ctx);
            default -> afterProbes(ctx);
        }
    }

    private void sendPing(ChannelHandlerContext ctx) {
        probe = Probe.PING;
        probeStepStartedAt = clock.getAsLong();
        if (protocol >= Versions.V1_17) {
            expectedPing = random.nextInt();
            write(ctx, State.PLAY, "PING", out -> out.writeInt(expectedPing));
        } else {
            expectedPing = -1 - random.nextInt(Short.MAX_VALUE);
            write(ctx, State.PLAY, "WINDOW_CONFIRMATION", out -> {
                out.writeByte(0);
                out.writeShort(expectedPing);
                out.writeBoolean(false);
            });
        }
        ctx.flush();
    }

    private void askForSlot(ChannelHandlerContext ctx) {
        probe = Probe.SLOT;
        probeStepStartedAt = clock.getAsLong();
        expectedSlot = (currentSlot + 1 + random.nextInt(7)) % 8;
        writeHeldSlot(ctx, -1);
        writeHeldSlot(ctx, expectedSlot);
        ctx.flush();
    }

    private void writeHeldSlot(ChannelHandlerContext ctx, int slot) {
        write(ctx, State.PLAY, "HELD_ITEM_CHANGE", out -> {
            if (protocol >= Versions.V1_21_4) {
                ProtocolBuffers.writeVarInt(out, slot);
            } else {
                out.writeByte(slot);
            }
        });
    }

    private void afterProbes(ChannelHandlerContext ctx) {
        if (captchaMillis > 0 && (fallForgiven || captchaRequired.getAsBoolean())) {
            probe = Probe.CAPTCHA;
            captchaDeadline = clock.getAsLong() + captchaMillis;
            deadline = Math.max(deadline, captchaDeadline);
            mapId = random.nextInt(1, 30000);
            showCaptcha(ctx);
            return;
        }
        startVehicle(ctx);
    }

    private void showCaptcha(ChannelHandlerContext ctx) {
        captcha = dev.sintesi.captcha.CaptchaImage.random(random);
        listener.captchaShown(name, captcha.code());
        byte[] pixels = captcha.pixels();
        int slot = currentSlot;
        write(ctx, State.PLAY, "MAP_DATA", out -> dev.sintesi.protocol.packet.CaptchaPackets.mapData(
                out, protocol, mapId, pixels, dev.sintesi.captcha.CaptchaImage.SIZE));
        write(ctx, State.PLAY, "SET_SLOT", out -> dev.sintesi.protocol.packet.CaptchaPackets.mapInHand(
                out, protocol, slot, mapId));
        ctx.flush();
    }

    private void startVehicle(ChannelHandlerContext ctx) {
        if (!vehicleCheck) {
            probe = Probe.DONE;
            checkPass(ctx);
            return;
        }
        probe = Probe.VEHICLE;
        probeStepStartedAt = clock.getAsLong();
        do {
            vehicleId = random.nextInt(1, Integer.MAX_VALUE);
        } while (vehicleId == playerEntityId);
        boatY = world.groundY() + 600 + random.nextInt(200) + random.nextDouble();
        double x = world.spawnX();
        double z = world.spawnZ();
        write(ctx, State.PLAY, "SPAWN_ENTITY",
                out -> dev.sintesi.protocol.packet.VehiclePackets.spawnBoat(out, protocol, vehicleId, x, boatY, z));
        write(ctx, State.PLAY, dev.sintesi.protocol.packet.VehiclePackets.mountPacket(protocol),
                out -> dev.sintesi.protocol.packet.VehiclePackets.mount(out, protocol, vehicleId, playerEntityId));
        ctx.flush();
    }

    private void onBoatMove(ChannelHandlerContext ctx, double y) {
        double motion = y - boatY;
        boatY = y;
        if (!boatFalling) {
            if (motion == 0) {
                if (++boatStillMoves > BOAT_STILL_LIMIT) {
                    finish(ctx, false, "vehicle not falling");
                }
                return;
            }
            if (Math.abs(motion + BOAT_GRAVITY) > BOAT_TOLERANCE) {
                finish(ctx, false, "vehicle gravity");
                return;
            }
            boatFalling = true;
        } else if (Math.abs(motion - (boatMotion - BOAT_GRAVITY)) > BOAT_TOLERANCE) {
            finish(ctx, false, "vehicle gravity");
            return;
        }
        boatMotion = motion;
        boatMoves++;
        checkBoat(ctx);
    }

    private void checkBoat(ChannelHandlerContext ctx) {
        boolean enough = protocol >= Versions.V1_9
                ? boatMoves >= BOAT_MOVES_REQUIRED && paddles >= 1
                : vehicleInputs >= BOAT_MOVES_REQUIRED;
        if (probe != Probe.VEHICLE || !enough) {
            return;
        }
        probe = Probe.DISMOUNT;
        probeStepStartedAt = clock.getAsLong();
        write(ctx, State.PLAY, "DESTROY_ENTITIES",
                out -> dev.sintesi.protocol.packet.VehiclePackets.destroy(out, protocol, vehicleId));
        ctx.flush();
    }

    private void askForSwing(ChannelHandlerContext ctx) {
        probe = Probe.SWING;
        probeStepStartedAt = clock.getAsLong();
        write(ctx, State.PLAY, "ENTITY_ANIMATION", out -> {
            ProtocolBuffers.writeVarInt(out, playerEntityId);
            out.writeByte(0);
        });
        ctx.flush();
    }

    public String describeProgress() {
        return progress();
    }

    private String progress() {
        return "teleport " + (teleportConfirmed ? "confirmed" : "pending")
                + ", moves " + moves
                + ", fall " + (landed ? "landed" : validator.synced() ? "synced" : "not synced")
                + ", keepalive " + keepAlivesAnswered + "/" + keepAlivesSent
                + ", brand " + brandReceived
                + ", settings " + settingsReceived
                + (protocol >= Versions.V1_21_2 ? ", tick ends " + tickEnds : "")
                + ", probe " + probe.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean validBrand(String brand) {
        if (brand.isEmpty()) {
            return false;
        }
        for (int i = 0; i < brand.length(); i++) {
            char c = brand.charAt(i);
            if ((c < ' ' || c > '~') && c != '\u00a7') {
                return false;
            }
        }
        return true;
    }

    private boolean confirmedPositionMatches(ByteBuf body) {
        double x = body.readDouble();
        double y = body.readDouble();
        double z = body.readDouble();
        float yaw = body.readFloat();
        float pitch = body.readFloat();
        return Math.abs(x - world.spawnX()) < POSITION_ECHO_TOLERANCE
                && Math.abs(y - world.spawnY()) < POSITION_ECHO_TOLERANCE
                && Math.abs(z - world.spawnZ()) < POSITION_ECHO_TOLERANCE
                && yaw == 0f && pitch == 0f;
    }

    private long elapsedTicks() {
        return (clock.getAsLong() - playStartedAt) / TICK_MILLIS;
    }

    private boolean countMovement(ChannelHandlerContext ctx, Movement movement) {
        if (!movement.finite()) {
            finish(ctx, false, "invalid coordinates");
            return false;
        }
        if (!movement.validRotation()) {
            finish(ctx, false, "invalid rotation");
            return false;
        }
        if (++movementPackets > elapsedTicks() + TIMER_SLACK_TICKS) {
            finish(ctx, false, "too many movement packets");
            return false;
        }
        return true;
    }

    private void onLook(ChannelHandlerContext ctx, Movement movement) {
        countMovement(ctx, movement);
    }

    private void onMovement(ChannelHandlerContext ctx, Movement movement) {
        if (!countMovement(ctx, movement)) {
            return;
        }
        moves++;
        if (protocol >= Versions.V1_9 && !teleportConfirmed) {
            return;
        }
        if (protocol >= Versions.V1_21_2) {
            if (teleportResponsePending) {
                teleportResponsePending = false;
            } else if (awaitingTickEnd) {
                finish(ctx, false, "missing client tick end");
                return;
            } else {
                awaitingTickEnd = true;
            }
        }
        if (probe == Probe.DISMOUNT && movement.hasPosition()) {
            if (movement.onGround() || movement.y() > boatY + 1.0) {
                finish(ctx, false, "invalid dismount");
                return;
            }
            probe = Probe.DONE;
            checkPass(ctx);
            return;
        }
        if (landed) {
            return;
        }
        Result result = validator.accept(movement.x(), movement.y(), movement.z(), movement.onGround());
        if (result == Result.FAILED && captchaOnFallFailure && captchaMillis > 0) {
            fallForgiven = true;
            landed = true;
            checkPass(ctx);
        } else if (result == Result.FAILED) {
            finish(ctx, false, validator.failure());
        } else if (result == Result.LANDED) {
            landed = true;
            checkPass(ctx);
        }
    }

    private void checkPass(ChannelHandlerContext ctx) {
        boolean confirmed = protocol < Versions.V1_9 || teleportConfirmed;
        boolean ticking = protocol < Versions.V1_21_2 || tickEnds >= 1;
        boolean ready = landed && confirmed && ticking && keepAlivesAnswered >= 1 && brandReceived && settingsReceived;
        if (ready && probe == Probe.PENDING) {
            if (protocolChecks) {
                startProbe(ctx);
            } else {
                afterProbes(ctx);
            }
            return;
        }
        if (ready && probe == Probe.DONE) {
            finish(ctx, true, null);
        }
    }

    private void startConfiguration(ChannelHandlerContext ctx) {
        phase = Phase.CONFIGURATION;
        for (LimboTemplate.ConfigPacket packet : template.configuration()) {
            ctx.write(sender.constantFrame(ctx.alloc(), State.CONFIGURATION, packet.name(), packet.body()));
        }
        write(ctx, State.CONFIGURATION, "CONFIGURATION_END", out -> {
        });
        ctx.flush();
    }

    private void enterPlay(ChannelHandlerContext ctx) {
        phase = Phase.PLAY;
        playerEntityId = random.nextInt(1, Integer.MAX_VALUE);
        byte[] join = template.joinGameFor(playerEntityId);
        ctx.write(sender.frameBytes(ctx.alloc(), State.PLAY, "JOIN_GAME", join));
        if (protocol >= Versions.V1_14) {
            write(ctx, State.PLAY, "UPDATE_VIEW_POSITION", out -> {
                ProtocolBuffers.writeVarInt(out, world.chunkX());
                ProtocolBuffers.writeVarInt(out, world.chunkZ());
            });
        }
        if (template.hasLight()) {
            write(ctx, State.PLAY, "UPDATE_LIGHT", out -> {
                ProtocolBuffers.writeVarInt(out, world.chunkX());
                ProtocolBuffers.writeVarInt(out, world.chunkZ());
                out.writeBytes(template.light());
            });
        }
        int radius = protocol >= Versions.V1_20_3 ? 1 : 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = world.chunkX() + dx;
                int chunkZ = world.chunkZ() + dz;
                write(ctx, State.PLAY, "CHUNK_DATA", out -> {
                    out.writeInt(chunkX);
                    out.writeInt(chunkZ);
                    out.writeBytes(template.chunk());
                });
            }
        }
        int stone = world.block().stateId(protocol);
        for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
            for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                int x = world.platformX() + dx;
                int z = world.platformZ() + dz;
                write(ctx, State.PLAY, "BLOCK_CHANGE", out -> {
                    out.writeLong(PlayPackets.packPosition(protocol, x, world.platformY(), z));
                    ProtocolBuffers.writeVarInt(out, stone);
                });
            }
        }
        if (protocol >= Versions.V1_20_3) {
            write(ctx, State.PLAY, "CHANGE_GAME_STATE", out -> PlayPackets.changeGameState(out, 13, 0f));
        }
        teleportId = 1 + random.nextInt(1 << 20);
        playStartedAt = clock.getAsLong();
        write(ctx, State.PLAY, "PLAYER_POSITION_AND_LOOK", out -> PlayPackets.positionAndLook(
                out, protocol, world.spawnX(), world.spawnY(), world.spawnZ(), 0f, 0f, teleportId));
        nextKeepAliveAt = playStartedAt + FIRST_KEEP_ALIVE_DELAY_MILLIS;
    }

    private void sendKeepAlive(ChannelHandlerContext ctx) {
        keepAliveId = random.nextInt(1, Integer.MAX_VALUE);
        keepAliveSentAt = clock.getAsLong();
        keepAlivesSent++;
        nextKeepAliveAt = keepAliveSentAt + (probe == Probe.CAPTCHA ? CAPTCHA_KEEP_ALIVE_MILLIS : 1000);
        write(ctx, State.PLAY, "KEEP_ALIVE", out -> PlayPackets.keepAlive(out, protocol, keepAliveId));
    }

    private void tick(ChannelHandlerContext ctx) {
        if (phase == Phase.DONE) {
            return;
        }
        long now = clock.getAsLong();
        if (probe == Probe.WAIT && now >= nextProbeAt) {
            runProbe(ctx, probePlan.get(probeIndex++));
        }
        if (probe == Probe.CAPTCHA && now > captchaDeadline) {
            finish(ctx, false, "captcha timeout");
            return;
        }
        if (now > deadline) {
            finish(ctx, false, "timeout (" + progress() + ")");
            return;
        }
        if (phase != Phase.PLAY) {
            return;
        }
        if ((probe == Probe.PING || probe == Probe.SLOT || probe == Probe.SWING || probe == Probe.VEHICLE
                || probe == Probe.DISMOUNT)
                && now - probeStepStartedAt > PROBE_STEP_MILLIS) {
            finish(ctx, false, "no " + probe.name().toLowerCase(java.util.Locale.ROOT) + " response");
            return;
        }
        if (keepAlivesSent > keepAlivesAnswered && now - keepAliveSentAt > KEEP_ALIVE_TIMEOUT_MILLIS) {
            finish(ctx, false, "keepalive timeout");
            return;
        }
        if (keepAlivesSent == keepAlivesAnswered && (keepAlivesSent < 2 || probe == Probe.CAPTCHA)
                && now >= nextKeepAliveAt) {
            sendKeepAlive(ctx);
            ctx.flush();
        }
    }

    private void finish(ChannelHandlerContext ctx, boolean passed, String reason) {
        Phase previous = phase;
        phase = Phase.DONE;
        if (ticker != null) {
            ticker.cancel(false);
        }
        Text message = passed ? messages.passed() : messages.failed();
        State state = previous == Phase.LOGIN_ACK ? State.LOGIN
                : previous == Phase.CONFIGURATION ? State.CONFIGURATION : State.PLAY;
        boolean transfer = passed && transferHost != null && state == State.PLAY;
        if (transfer) {
            ctx.writeAndFlush(sender.frame(ctx.alloc(), State.PLAY, "TRANSFER", out -> {
                ProtocolBuffers.writeString(out, transferHost);
                ProtocolBuffers.writeVarInt(out, transferPort);
            })).addListener(ChannelFutureListener.CLOSE);
            listener.passed(name, true);
            return;
        }
        Runnable kick = () -> {
            if (!ctx.channel().isActive()) {
                return;
            }
            ByteBuf frame = sender.frame(ctx.alloc(), state, "DISCONNECT", out -> {
                if (state == State.LOGIN) {
                    DisconnectPackets.login(out, protocol, message);
                } else {
                    DisconnectPackets.playOrConfiguration(out, protocol, message);
                }
            });
            ctx.writeAndFlush(frame).addListener(ChannelFutureListener.CLOSE);
        };
        if (passed) {
            kick.run();
        } else {
            ctx.channel().config().setAutoRead(false);
            long delay = FAILURE_DELAY_MIN_MILLIS + random.nextInt(FAILURE_DELAY_SPREAD_MILLIS);
            ctx.executor().schedule(kick, delay, TimeUnit.MILLISECONDS);
        }
        if (passed) {
            listener.passed(name, false);
        } else {
            listener.failed(name, reason);
        }
    }

    private void write(ChannelHandlerContext ctx, State state, String packet, java.util.function.Consumer<ByteBuf> body) {
        ctx.write(sender.frame(ctx.alloc(), state, packet, body));
    }
}
