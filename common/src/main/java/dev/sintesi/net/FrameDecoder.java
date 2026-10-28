package dev.sintesi.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

public final class FrameDecoder extends ByteToMessageDecoder {

    public static final String NAME = "sintesi-frame-decoder";

    private static final int MAX_LENGTH_BYTES = 3;

    private volatile int maxFrameLength;

    public FrameDecoder(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    public void maxFrameLength(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        int length = 0;
        for (int i = 0; i < MAX_LENGTH_BYTES; i++) {
            if (!in.isReadable()) {
                in.resetReaderIndex();
                return;
            }
            byte read = in.readByte();
            length |= (read & 0x7F) << (i * 7);
            if ((read & 0x80) == 0) {
                if (length <= 0 || length > maxFrameLength) {
                    reject(ctx, in);
                    return;
                }
                if (in.readableBytes() < length) {
                    in.resetReaderIndex();
                    return;
                }
                out.add(in.readRetainedSlice(length));
                return;
            }
        }
        reject(ctx, in);
    }

    private static void reject(ChannelHandlerContext ctx, ByteBuf in) {
        in.skipBytes(in.readableBytes());
        ctx.close();
    }
}
