package dev.sintesi.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.sintesi.platform.Audience;
import dev.sintesi.platform.Platform;
import dev.sintesi.platform.PlatformPlayer;
import dev.sintesi.platform.SintesiCommands;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.title.Title;

final class VelocityPlatform implements Platform {

    private final ProxyServer server;

    VelocityPlatform(ProxyServer server) {
        this.server = server;
    }

    @Override
    public Collection<VelocityPlayer> players() {
        return server.getAllPlayers().stream().map(VelocityPlayer::new).toList();
    }

    @Override
    public Optional<VelocityPlayer> player(String name) {
        return server.getPlayer(name).map(VelocityPlayer::new);
    }

    @Override
    public Audience console() {
        return new VelocitySource(server.getConsoleCommandSource());
    }

    static Audience wrap(CommandSource source) {
        return source instanceof Player player ? new VelocityPlayer(player) : new VelocitySource(source);
    }

    record VelocitySource(CommandSource source) implements Audience {
        @Override
        public boolean hasPermission(String permission) {
            return source.hasPermission(permission);
        }

        @Override
        public void sendMessage(String text) {
            source.sendMessage(Chat.of(text));
        }
    }

    record VelocityPlayer(Player player) implements PlatformPlayer {
        @Override
        public boolean hasPermission(String permission) {
            return player.hasPermission(permission);
        }

        @Override
        public void sendMessage(String text) {
            player.sendMessage(Chat.of(text));
        }

        @Override
        public UUID uniqueId() {
            return player.getUniqueId();
        }

        @Override
        public String name() {
            return player.getUsername();
        }

        @Override
        public InetAddress address() {
            return player.getRemoteAddress().getAddress();
        }

        @Override
        public void sendActionBar(String text) {
            player.sendActionBar(Chat.of(text));
        }

        @Override
        public void showTitle(String title, String subtitle, int fadeInMillis, int stayMillis, int fadeOutMillis) {
            player.showTitle(Title.title(Chat.of(title), Chat.of(subtitle), Title.Times.times(
                    Duration.ofMillis(fadeInMillis), Duration.ofMillis(stayMillis), Duration.ofMillis(fadeOutMillis))));
        }
    }

    record Command(SintesiCommands commands) implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) {
            return commands.hasPermission(wrap(invocation.source()));
        }

        @Override
        public void execute(Invocation invocation) {
            commands.execute(wrap(invocation.source()), invocation.arguments());
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return commands.suggest(wrap(invocation.source()), invocation.arguments());
        }
    }
}
