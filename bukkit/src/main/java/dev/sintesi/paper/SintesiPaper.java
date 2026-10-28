package dev.sintesi.paper;

import dev.faststats.Metrics;
import dev.faststats.bukkit.BukkitContext;
import dev.sintesi.platform.SintesiRuntime;
import dev.sintesi.platform.SintesiServices;
import dev.sintesi.platform.Telemetry;
import java.lang.reflect.Field;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SintesiPaper extends JavaPlugin {

    private static final String PAPER_HOOK = "io.papermc.paper.network.ChannelInitializeListenerHolder";

    private final BukkitContext stats = new BukkitContext.Factory(this, Telemetry.TOKEN)
            .errorTrackerService(Telemetry.errorTracker())
            .metrics(Metrics.Factory::create)
            .create();
    private SintesiRuntime runtime;
    private PaperInjector paperInjector;
    private SpigotInjector spigotInjector;

    @Override
    public void onEnable() {
        stats.ready();
        try {
            String proxy = proxyMode();
            if (proxy != null) {
                getLogger().severe("Sintesi is NOT active: this server runs behind a proxy (" + proxy
                        + "), every player shares the proxy address. Install Sintesi on the proxy instead");
                return;
            }
            if (!Boolean.getBoolean("sintesi.spigot") && hasClass(PAPER_HOOK)) {
                startPaper();
            } else {
                startSpigot();
            }
        } catch (Exception | LinkageError failure) {
            getLogger().log(Level.SEVERE, "Sintesi failed to start, the server is NOT protected", failure);
        }
    }

    private void startPaper() throws ReflectiveOperationException {
        PaperPlatform platform = new PaperPlatform(getServer());
        getServer().getPluginManager().registerEvents(platform, this);
        SintesiServices services = SintesiServices.start(getDataFolder().toPath(), getLogger()::info, platform,
                version());
        runtime = services.runtime();
        paperInjector = new PaperInjector();
        paperInjector.inject(runtime::core);
        register(new PaperPlatform.SintesiCommand(services.commands()));
        services.announce(getServer().getName(), "the Paper channel initializer");
    }

    private void startSpigot() throws ReflectiveOperationException {
        SpigotPlatform platform = new SpigotPlatform(getServer());
        getServer().getPluginManager().registerEvents(platform, this);
        SintesiServices services = SintesiServices.start(getDataFolder().toPath(), getLogger()::info, platform,
                version());
        runtime = services.runtime();
        spigotInjector = new SpigotInjector();
        register(new SpigotPlatform.SintesiCommand(services.commands()));
        if (spigotInjector.inject(runtime::core)) {
            services.announce("Spigot", "the server channel");
            return;
        }
        getLogger().warning("The server port is not open yet, Sintesi will protect it as soon as startup finishes");
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onLoad(org.bukkit.event.server.ServerLoadEvent event) {
                try {
                    if (spigotInjector.inject(runtime::core)) {
                        services.announce("Spigot", "the server channel");
                    } else {
                        getLogger().severe("Sintesi could not find the server channel, the server is NOT protected");
                    }
                } catch (ReflectiveOperationException failure) {
                    getLogger().log(Level.SEVERE, "Sintesi failed to start, the server is NOT protected", failure);
                }
            }
        }, this);
    }

    private void register(org.bukkit.command.TabExecutor executor) {
        PluginCommand command = getCommand("sintesi");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    @SuppressWarnings("deprecation")
    private String version() {
        return getDescription().getVersion();
    }

    private boolean hasClass(String name) {
        try {
            Class.forName(name, false, getServer().getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    @Override
    public void onDisable() {
        try {
            if (paperInjector != null) {
                paperInjector.restore();
            }
        } catch (ReflectiveOperationException failure) {
            getLogger().log(Level.WARNING, "Sintesi could not remove its channel listener", failure);
        }
        if (spigotInjector != null) {
            spigotInjector.restore();
        }
        if (runtime != null) {
            runtime.close();
        }
        stats.shutdown();
    }

    private String proxyMode() {
        if (getServer().spigot().getConfig().getBoolean("settings.bungeecord")) {
            return "spigot.yml settings.bungeecord";
        }
        try {
            Class<?> global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration", true,
                    getServer().getClass().getClassLoader());
            Object proxies = field(global.getMethod("get").invoke(null), "proxies");
            if ((Boolean) field(field(proxies, "velocity"), "enabled")) {
                return "paper-global.yml proxies.velocity.enabled";
            }
        } catch (ReflectiveOperationException | ClassCastException | NullPointerException ignored) {
        }
        return null;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getField(name);
        return field.get(owner);
    }
}
