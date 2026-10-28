package dev.sintesi.net;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class ClientAddress {

    static final AttributeKey<InetSocketAddress> PROXIED = AttributeKey.valueOf("sintesi-client-address");

    private ClientAddress() {
    }

    static boolean isLocalProxy(Channel channel) {
        return channel.attr(PROXIED).get() == null && channel.remoteAddress() instanceof InetSocketAddress peer
                && peer.getAddress() != null && FloodGuard.exempt(peer.getAddress());
    }

    public static SocketAddress of(Channel channel) {
        InetSocketAddress proxied = channel.attr(PROXIED).get();
        return proxied != null ? proxied : channel.remoteAddress();
    }
}
