package dev.sintesi.bungee;

import dev.sintesi.net.SintesiCore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

final class BungeeInjector {

    private static final String ACCEPTOR = "net.md_5.bungee.protocol.channel.ChannelAcceptor";
    private static final String INITIALIZER = "net.md_5.bungee.protocol.channel.BungeeChannelInitializer";
    private static final String PIPELINE_UTILS = "net.md_5.bungee.netty.PipelineUtils";

    private final Object proxyServer;
    private Runnable restore;

    BungeeInjector(Object proxyServer) {
        this.proxyServer = proxyServer;
    }

    String inject(Supplier<SintesiCore> core) throws ReflectiveOperationException {
        Method unsafeMethod = findMethod(proxyServer.getClass(), "unsafe");
        if (unsafeMethod != null) {
            injectModern(unsafeMethod.invoke(proxyServer), core);
            return "frontend channel initializer";
        }
        injectLegacy(core);
        return "PipelineUtils.SERVER_CHILD";
    }

    void restore() {
        if (restore != null) {
            restore.run();
            restore = null;
        }
    }

    private void injectModern(Object unsafe, Supplier<SintesiCore> core) throws ReflectiveOperationException {
        ClassLoader loader = proxyServer.getClass().getClassLoader();
        Class<?> acceptorType = Class.forName(ACCEPTOR, true, loader);
        Class<?> initializerType = Class.forName(INITIALIZER, true, loader);
        Method getter = unsafe.getClass().getMethod("getFrontendChannelInitializer");
        Method setter = unsafe.getClass().getMethod("setFrontendChannelInitializer", initializerType);
        getter.setAccessible(true);
        setter.setAccessible(true);
        Object original = getter.invoke(unsafe);
        Object originalAcceptor = initializerType.getMethod("getChannelAcceptor").invoke(original);
        Method accept = acceptorType.getMethod("accept", Channel.class);
        Object acceptor = Proxy.newProxyInstance(acceptorType.getClassLoader(), new Class<?>[] {acceptorType},
                (instance, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(instance, method, arguments);
                    }
                    Channel channel = (Channel) arguments[0];
                    boolean accepted;
                    try {
                        accepted = (Boolean) accept.invoke(originalAcceptor, channel);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (accepted) {
                        core.get().install(channel);
                    } else {
                        core.get().rejectedByPlatform();
                    }
                    return accepted;
                });
        Object wrapped = initializerType.getMethod("create", acceptorType).invoke(null, acceptor);
        setter.invoke(unsafe, wrapped);
        restore = () -> {
            try {
                setter.invoke(unsafe, original);
            } catch (ReflectiveOperationException ignored) {
            }
        };
    }

    @SuppressWarnings("unchecked")
    private void injectLegacy(Supplier<SintesiCore> core) throws ReflectiveOperationException {
        Class<?> pipelineUtils = Class.forName(PIPELINE_UTILS, true, proxyServer.getClass().getClassLoader());
        Field field = pipelineUtils.getDeclaredField("SERVER_CHILD");
        field.setAccessible(true);
        ChannelInitializer<Channel> original = (ChannelInitializer<Channel>) field.get(null);
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
                if (channel.isOpen()) {
                    core.get().install(channel);
                } else {
                    core.get().rejectedByPlatform();
                }
            }
        };
        StaticWriter writer = new StaticWriter(field);
        writer.write(wrapped);
        restore = () -> {
            try {
                writer.write(original);
            } catch (ReflectiveOperationException ignored) {
            }
        };
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> contract : current.getInterfaces()) {
                Method found = publicMethod(contract, name);
                if (found != null) {
                    return found;
                }
            }
            Method found = publicMethod(current, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Method publicMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static Object objectMethod(Object instance, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> instance == arguments[0];
            case "hashCode" -> System.identityHashCode(instance);
            default -> "SintesiChannelAcceptor";
        };
    }

    private static final class StaticWriter {

        private final Object unsafe;
        private final Method putObject;
        private final Object base;
        private final long offset;

        StaticWriter(Field field) throws ReflectiveOperationException {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field instance = unsafeType.getDeclaredField("theUnsafe");
            instance.setAccessible(true);
            this.unsafe = instance.get(null);
            this.base = unsafeType.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
            this.offset = (Long) unsafeType.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field);
            this.putObject = unsafeType.getMethod("putObjectVolatile", Object.class, long.class, Object.class);
        }

        void write(Object value) throws ReflectiveOperationException {
            putObject.invoke(unsafe, base, offset, value);
        }
    }
}
