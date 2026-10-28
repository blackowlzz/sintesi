package dev.sintesi.paper;

import dev.sintesi.net.SintesiCore;
import io.netty.channel.Channel;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;

final class PaperInjector {

    private static final String HOLDER = "io.papermc.paper.network.ChannelInitializeListenerHolder";
    private static final String LISTENER = "io.papermc.paper.network.ChannelInitializeListener";
    private static final Key KEY = Key.key("sintesi", "antibot");

    private Method remove;

    void inject(Supplier<SintesiCore> core) throws ReflectiveOperationException {
        ClassLoader loader = Bukkit.getServer().getClass().getClassLoader();
        Class<?> holder = Class.forName(HOLDER, true, loader);
        Class<?> listenerType = Class.forName(LISTENER, true, loader);
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                (instance, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(instance, method, arguments);
                    }
                    core.get().install((Channel) arguments[0]);
                    return null;
                });
        holder.getMethod("addListener", Key.class, listenerType).invoke(null, KEY, listener);
        remove = holder.getMethod("removeListener", Key.class);
    }

    void restore() throws ReflectiveOperationException {
        if (remove != null) {
            remove.invoke(null, KEY);
            remove = null;
        }
    }

    private static Object objectMethod(Object instance, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> instance == arguments[0];
            case "hashCode" -> System.identityHashCode(instance);
            default -> "SintesiChannelInitializeListener";
        };
    }
}
