package dev.sintesi.protocol;

import dev.sintesi.protocol.PacketIds.Side;
import dev.sintesi.protocol.PacketIds.State;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class PacketSender {

    private static final Map<CachedBody, byte[]> COMPRESSED = new ConcurrentHashMap<>();

    private final PacketIds ids;
    private final int protocol;
    private int threshold = -1;

    public PacketSender(PacketIds ids, int protocol) {
        this.ids = ids;
        this.protocol = protocol;
    }

    public void compress(int threshold) {
        this.threshold = threshold;
    }

    public boolean compressing() {
        return threshold >= 0;
    }

    public int threshold() {
        return threshold;
    }

    public ByteBuf frame(ByteBufAllocator allocator, State state, String name, Consumer<ByteBuf> body) {
        int id = idOf(state, name);
        ByteBuf payload = allocator.buffer();
        try {
            ProtocolBuffers.writeVarInt(payload, id);
            body.accept(payload);
            if (threshold >= 0) {
                return Compression.frame(allocator, payload, threshold);
            }
            ByteBuf out = allocator.buffer(payload.readableBytes() + 3);
            ProtocolBuffers.writeVarInt(out, payload.readableBytes());
            out.writeBytes(payload);
            return out;
        } finally {
            payload.release();
        }
    }

    public ByteBuf frameBytes(ByteBufAllocator allocator, State state, String name, byte[] body) {
        return frame(allocator, state, name, out -> out.writeBytes(body));
    }

    public ByteBuf constantFrame(ByteBufAllocator allocator, State state, String name, byte[] body) {
        int id = idOf(state, name);
        if (threshold < 0 || ProtocolBuffers.varIntSize(id) + body.length < threshold) {
            return frameBytes(allocator, state, name, body);
        }
        byte[] frame = COMPRESSED.computeIfAbsent(new CachedBody(id, body), key -> {
            ByteBuf payload = Unpooled.buffer(body.length + 5);
            ProtocolBuffers.writeVarInt(payload, id);
            payload.writeBytes(body);
            return Compression.compressedFrame(ByteBufUtil.getBytes(payload));
        });
        return Unpooled.wrappedBuffer(frame);
    }

    private int idOf(State state, String name) {
        int id = ids.id(state, Side.TO_CLIENT, name, protocol);
        if (id < 0) {
            throw new IllegalStateException(state + " " + name + " is absent in protocol " + protocol);
        }
        return id;
    }

    private record CachedBody(int id, byte[] body) {
    }

    public static ByteBuf frameOf(ByteBufAllocator allocator, byte[] payload) {
        ByteBuf out = allocator.buffer(payload.length + 3);
        ProtocolBuffers.writeVarInt(out, payload.length);
        out.writeBytes(payload);
        return out;
    }
}
