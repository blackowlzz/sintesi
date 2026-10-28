package dev.sintesi.net;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

public final class ProxyHeaderHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "sintesi-proxy-header";

    private static final String DECODER = "io.netty.handler.codec.haproxy.HAProxyMessageDecoder";

    private boolean activePending;

    static String decoderName(ChannelPipeline pipeline) {
        for (Map.Entry<String, ChannelHandler> entry : pipeline.toMap().entrySet()) {
            if (entry.getValue().getClass().getName().equals(DECODER)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        activePending = true;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof HAProxyMessage header) {
            InetSocketAddress source = sourceOf(header);
            if (source != null) {
                ctx.channel().attr(ClientAddress.PROXIED).set(source);
            }
        }
        releaseActive(ctx);
        if (ctx.channel().isOpen()) {
            ctx.fireChannelRead(message);
        } else {
            ReferenceCountUtil.release(message);
        }
        ctx.pipeline().remove(this);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        releaseActive(ctx);
        ctx.fireChannelInactive();
    }

    private void releaseActive(ChannelHandlerContext ctx) {
        if (activePending) {
            activePending = false;
            ctx.fireChannelActive();
        }
    }

    private static InetSocketAddress sourceOf(HAProxyMessage header) {
        if (header.command() != HAProxyCommand.PROXY || header.sourceAddress() == null) {
            return null;
        }
        HAProxyProxiedProtocol.AddressFamily family = header.proxiedProtocol().addressFamily();
        if (family != HAProxyProxiedProtocol.AddressFamily.AF_IPv4
                && family != HAProxyProxiedProtocol.AddressFamily.AF_IPv6) {
            return null;
        }
        InetAddress source = NumericAddress.parse(header.sourceAddress());
        return source == null ? null : new InetSocketAddress(source, header.sourcePort());
    }
}
