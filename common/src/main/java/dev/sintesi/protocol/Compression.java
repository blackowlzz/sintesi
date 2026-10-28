package dev.sintesi.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class Compression {

    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(() -> new Deflater(6));
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);

    private Compression() {
    }

    public static ByteBuf frame(ByteBufAllocator allocator, ByteBuf payload, int threshold) {
        int length = payload.readableBytes();
        if (length < threshold) {
            ByteBuf out = allocator.buffer(length + 4);
            ProtocolBuffers.writeVarInt(out, length + 1);
            out.writeByte(0);
            out.writeBytes(payload);
            return out;
        }
        return Unpooled.wrappedBuffer(compressedFrame(ByteBufUtil.getBytes(payload)));
    }

    public static byte[] compressedFrame(byte[] payload) {
        byte[] compressed = deflate(payload);
        ByteBuf out = Unpooled.buffer(compressed.length + 10);
        ProtocolBuffers.writeVarInt(out, ProtocolBuffers.varIntSize(payload.length) + compressed.length);
        ProtocolBuffers.writeVarInt(out, payload.length);
        out.writeBytes(compressed);
        return ByteBufUtil.getBytes(out);
    }

    public static ByteBuf unwrap(ByteBuf frame, int threshold, int maxLength) {
        int length = ProtocolBuffers.readVarInt(frame);
        if (length == 0) {
            return frame;
        }
        if (length < threshold || length > maxLength) {
            throw new DecodeException("compressed packet size " + length);
        }
        byte[] input = ByteBufUtil.getBytes(frame);
        byte[] output = new byte[length];
        Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(input);
        try {
            int produced = inflater.inflate(output);
            if (produced != length || !inflater.finished()) {
                throw new DecodeException("compressed packet length mismatch");
            }
        } catch (DataFormatException invalid) {
            throw new DecodeException("invalid compressed packet");
        }
        return Unpooled.wrappedBuffer(output);
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 4 + 64);
        byte[] chunk = new byte[8192];
        while (!deflater.finished()) {
            out.write(chunk, 0, deflater.deflate(chunk));
        }
        return out.toByteArray();
    }
}
