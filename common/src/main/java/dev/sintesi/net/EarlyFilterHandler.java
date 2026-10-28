package dev.sintesi.net;

import dev.sintesi.attack.AttackMonitor;
import dev.sintesi.stats.Statistics;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Sharable
public final class EarlyFilterHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "sintesi-early-filter";
    public static final AttributeKey<Blocklist.Entry> NOTICE = AttributeKey.valueOf("sintesi-block-notice");

    private final ConnectionGate gate;
    private final AttackMonitor monitor;
    private final Statistics statistics;
    private final FloodGuard flood;

    public EarlyFilterHandler(ConnectionGate gate, AttackMonitor monitor, Statistics statistics, FloodGuard flood) {
        this.gate = gate;
        this.monitor = monitor;
        this.statistics = statistics;
        this.flood = flood;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        statistics.connection();
        monitor.recordConnection();
        SocketAddress remote = ClientAddress.of(ctx.channel());
        if (!(remote instanceof InetSocketAddress inet) || inet.getAddress() == null) {
            super.channelActive(ctx);
            return;
        }
        long key = SubnetKey.of(inet.getAddress());
        long address = SubnetKey.address(inet.getAddress());
        Admission admission = gate.admit(key, address);
        if (admission == Admission.FLOOD && ClientAddress.isLocalProxy(ctx.channel())) {
            flood.warnLoopback();
            admission = gate.admit(key, address, true);
        }
        if (admission == Admission.FLOOD) {
            flood.block(address, inet.getAddress());
        }
        if (admission == Admission.FLOOD || admission == Admission.BLOCKED) {
            Blocklist.Entry notice = flood.acquireNotice(address);
            if (notice != null) {
                ctx.channel().attr(NOTICE).set(notice);
                super.channelActive(ctx);
                return;
            }
        }
        if (admission != Admission.ACCEPT) {
            statistics.blocked();
            ctx.channel().config().setAutoRead(false);
            super.channelActive(ctx);
            ctx.close();
            return;
        }
        ctx.pipeline().addAfter(NAME, ReleaseHandler.NAME, new ReleaseHandler(gate, key));
        super.channelActive(ctx);
    }
}
