package dev.sintesi.velocity;

import dev.sintesi.net.SintesiCore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

final class VelocityInjector {

    private final Object holder;
    private final Method setter;
    private final ChannelInitializer<Channel> original;

    @SuppressWarnings("unchecked")
    VelocityInjector(Object proxyServer) throws ReflectiveOperationException {
        Field managerField = proxyServer.getClass().getDeclaredField("cm");
        managerField.setAccessible(true);
        Object manager = managerField.get(proxyServer);
        this.holder = manager.getClass().getMethod("getServerChannelInitializer").invoke(manager);
        this.setter = holder.getClass().getMethod("set", ChannelInitializer.class);
        this.original = (ChannelInitializer<Channel>) holder.getClass().getMethod("get").invoke(holder);
    }

    void inject(Supplier<SintesiCore> core) throws ReflectiveOperationException {
        Method initialize = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
        initialize.setAccessible(true);
        ChannelInitializer<Channel> wrapped = new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {
                try {
                    initialize.invoke(original, channel);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause() instanceof Exception cause ? cause : failure;
                }
                core.get().install(channel);
            }
        };
        setter.invoke(holder, wrapped);
    }

    void restore() throws ReflectiveOperationException {
        setter.invoke(holder, original);
    }
}
