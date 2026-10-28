package dev.sintesi.bungee;

import dev.faststats.Metrics;
import dev.faststats.bungee.BungeeContext;
import dev.sintesi.platform.SintesiRuntime;
import dev.sintesi.platform.SintesiServices;
import dev.sintesi.platform.Telemetry;
import java.util.logging.Level;
import net.md_5.bungee.api.plugin.Plugin;

public final class SintesiBungee extends Plugin {

    private final BungeeContext stats = new BungeeContext.Factory(this, Telemetry.TOKEN)
            .errorTrackerService(Telemetry.errorTracker())
            .metrics(Metrics.Factory::create)
            .create();
    private SintesiRuntime runtime;
    private BungeeInjector injector;

    @Override
    public void onEnable() {
        try {
            BungeePlatform platform = new BungeePlatform(getProxy());
            SintesiServices services = SintesiServices.start(getDataFolder().toPath(), getLogger()::info, platform,
                    getDescription().getVersion());
            runtime = services.runtime();
            injector = new BungeeInjector(getProxy());
            String target = injector.inject(runtime::core);
            getProxy().getPluginManager().registerCommand(this,
                    new BungeePlatform.SintesiCommand(platform, services.commands()));
            services.announce(getProxy().getName(), "the " + target);
        } catch (Exception | LinkageError failure) {
            getLogger().log(Level.SEVERE, "Sintesi failed to start, the proxy is NOT protected", failure);
        }
        stats.ready();
    }

    @Override
    public void onDisable() {
        if (injector != null) {
            injector.restore();
        }
        if (runtime != null) {
            runtime.close();
        }
        stats.shutdown();
    }
}
