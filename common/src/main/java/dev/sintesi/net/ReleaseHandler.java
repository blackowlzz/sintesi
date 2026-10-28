package dev.sintesi.net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public final class ReleaseHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "sintesi-release";

    private final ConnectionGate gate;
    private final long subnetKey;
    private boolean released;

    public ReleaseHandler(ConnectionGate gate, long subnetKey) {
        this.gate = gate;
        this.subnetKey = subnetKey;
    }

    public void releaseNow() {
        if (!released) {
            released = true;
            gate.release(subnetKey);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseNow();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseNow();
    }
}
