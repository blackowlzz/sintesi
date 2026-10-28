package dev.sintesi.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.faststats.Metrics;
import dev.faststats.velocity.VelocityContext;
import dev.sintesi.platform.SintesiRuntime;
import dev.sintesi.platform.SintesiServices;
import dev.sintesi.platform.Telemetry;
import java.nio.file.Path;
import org.slf4j.Logger;

@Plugin(id = "sintesi", name = "Sintesi", version = SintesiVelocity.VERSION, description = "Antibot protection",
        authors = {"Sintesi"})
public final class SintesiVelocity {

    static final String VERSION = "1.0.0";

    private final ProxyServer server;
    private final Path dataDirectory;
    private final Logger logger;
    private final VelocityContext stats;
    private SintesiRuntime runtime;
    private VelocityInjector injector;

    @Inject
    public SintesiVelocity(ProxyServer server, @DataDirectory Path dataDirectory, Logger logger,
            VelocityContext.Builder statsBuilder) {
        this.server = server;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.stats = statsBuilder.token(Telemetry.TOKEN).errorTrackerService(Telemetry.errorTracker())
                .metrics(Metrics.Factory::create).create();
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            SintesiServices services = SintesiServices.start(dataDirectory, logger::info, new VelocityPlatform(server),
                    VERSION);
            runtime = services.runtime();
            injector = new VelocityInjector(server);
            injector.inject(runtime::core);
            CommandMeta meta = server.getCommandManager().metaBuilder("sintesi").aliases("antibot").plugin(this).build();
            server.getCommandManager().register(meta, new VelocityPlatform.Command(services.commands()));
            services.announce(server.getVersion().getName(), "the server channel initializer");
        } catch (Exception failure) {
            logger.error("Sintesi failed to start, the proxy is NOT protected", failure);
        }
        stats.ready();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        try {
            if (injector != null) {
                injector.restore();
            }
        } catch (ReflectiveOperationException failure) {
            logger.warn("Sintesi could not restore the channel initializer", failure);
        }
        if (runtime != null) {
            runtime.close();
        }
        stats.shutdown();
    }
}
