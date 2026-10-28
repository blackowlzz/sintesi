package dev.sintesi.paper;

import dev.sintesi.net.SintesiCore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Bukkit;

final class SpigotInjector {

    private static final String ACCEPTOR = "sintesi-acceptor";
    private static final String INSTALLER = "sintesi-installer";
    private static final Set<String> CONNECTION_TYPES = Set.of("ServerConnection", "ServerConnectionListener");

    private final List<Channel> servers = new ArrayList<>();

    boolean inject(Supplier<SintesiCore> core) throws ReflectiveOperationException {
        Object craftServer = Bukkit.getServer();
        Object minecraft = craftServer.getClass().getMethod("getServer").invoke(craftServer);
        Object connection = valueOfType(minecraft, field -> CONNECTION_TYPES.contains(field.getType().getSimpleName()));
        if (connection == null) {
            return false;
        }
        Object channels = valueOfType(connection, field -> List.class.isAssignableFrom(field.getType())
                && field.getGenericType() instanceof ParameterizedType generic
                && generic.getActualTypeArguments()[0].getTypeName().contains(ChannelFuture.class.getName()));
        if (!(channels instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        Acceptor acceptor = new Acceptor(core);
        synchronized (list) {
            for (Object entry : list) {
                Channel server = ((ChannelFuture) entry).channel();
                if (server.pipeline().get(ACCEPTOR) == null) {
                    server.pipeline().addFirst(ACCEPTOR, acceptor);
                    servers.add(server);
                }
            }
        }
        return !servers.isEmpty();
    }

    void restore() {
        for (Channel server : servers) {
            if (server.pipeline().get(ACCEPTOR) != null) {
                server.pipeline().remove(ACCEPTOR);
            }
        }
        servers.clear();
    }

    private interface FieldTest {
        boolean matches(Field field);
    }

    private static Object valueOfType(Object owner, FieldTest test) throws IllegalAccessException {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) && test.matches(field)) {
                    field.setAccessible(true);
                    return field.get(owner);
                }
            }
        }
        return null;
    }

    @Sharable
    private static final class Acceptor extends ChannelInboundHandlerAdapter {

        private final Supplier<SintesiCore> core;

        Acceptor(Supplier<SintesiCore> core) {
            this.core = core;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            if (message instanceof Channel child) {
                child.pipeline().addFirst(INSTALLER, new Installer(core));
            }
            ctx.fireChannelRead(message);
        }
    }

    private static final class Installer extends ChannelInboundHandlerAdapter {

        private final Supplier<SintesiCore> core;

        Installer(Supplier<SintesiCore> core) {
            this.core = core;
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            ctx.pipeline().remove(this);
            core.get().install(ctx.channel());
            super.channelRegistered(ctx);
        }
    }
}
