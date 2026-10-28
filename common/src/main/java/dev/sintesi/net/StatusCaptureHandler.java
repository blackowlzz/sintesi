package dev.sintesi.net;

import dev.sintesi.protocol.StatusCache;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.Arrays;
import java.util.function.LongSupplier;

final class StatusCaptureHandler extends ChannelOutboundHandlerAdapter {

    static final String NAME = "sintesi-status-capture";

    private static final int MAX_WRITES = 4;

    private final StatusCache cache;
    private final int protocol;
    private final String host;
    private final LongSupplier clock;
    private byte[] buffer = new byte[1024];
    private int length;
    private int writes;

    StatusCaptureHandler(StatusCache cache, int protocol, String host, LongSupplier clock) {
        this.cache = cache;
        this.protocol = protocol;
        this.host = host;
        this.clock = clock;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
        if (message instanceof ByteBuf data) {
            capture(ctx, data);
        }
        ctx.write(message, promise);
    }

    private void capture(ChannelHandlerContext ctx, ByteBuf data) {
        int readable = data.readableBytes();
        if (length + readable > StatusCache.MAX_FRAME + 5) {
            done(ctx);
            return;
        }
        if (length + readable > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + readable));
        }
        data.getBytes(data.readerIndex(), buffer, length, readable);
        length += readable;
        writes++;
        int complete = StatusCache.completeResponseLength(buffer, length);
        if (complete > 0) {
            cache.store(protocol, host, StatusCache.copy(buffer, complete), clock.getAsLong());
            done(ctx);
        } else if (complete < 0 || writes >= MAX_WRITES) {
            done(ctx);
        }
    }

    private void done(ChannelHandlerContext ctx) {
        buffer = null;
        ctx.pipeline().remove(this);
    }
}
