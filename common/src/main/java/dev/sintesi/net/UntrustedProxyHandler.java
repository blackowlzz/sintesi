package dev.sintesi.net;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

@Sharable
final class UntrustedProxyHandler extends ChannelInboundHandlerAdapter {

    static final String NAME = "sintesi-untrusted-proxy";
    static final UntrustedProxyHandler INSTANCE = new UntrustedProxyHandler();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().config().setAutoRead(false);
        super.channelActive(ctx);
        ctx.close();
    }
}
