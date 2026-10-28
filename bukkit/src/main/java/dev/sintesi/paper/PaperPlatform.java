package dev.sintesi.paper;

import dev.sintesi.platform.Audience;
import dev.sintesi.platform.Platform;
import dev.sintesi.platform.PlatformPlayer;
import dev.sintesi.platform.SintesiCommands;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.title.Title;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class PaperPlatform implements Platform, Listener {

    private final Map<UUID, Player> online = new ConcurrentHashMap<>();
    private final Server server;

    PaperPlatform(Server server) {
        this.server = server;
        for (Player player : server.getOnlinePlayers()) {
            online.put(player.getUniqueId(), player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        online.put(event.getPlayer().getUniqueId(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        online.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public Collection<PaperPlayer> players() {
        return online.values().stream().map(PaperPlayer::new).toList();
    }

    @Override
    public Optional<PaperPlayer> player(String name) {
        return online.values().stream().filter(player -> player.getName().equalsIgnoreCase(name)).findFirst()
                .map(PaperPlayer::new);
    }

    @Override
    public Audience console() {
        return new PaperSource(server.getConsoleSender());
    }

    static Audience wrap(CommandSender sender) {
        return sender instanceof Player player ? new PaperPlayer(player) : new PaperSource(sender);
    }

    record PaperSource(CommandSender sender) implements Audience {
        @Override
        public boolean hasPermission(String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        public void sendMessage(String text) {
            sender.sendMessage(Chat.of(text));
        }
    }

    record PaperPlayer(Player player) implements PlatformPlayer {
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
            return player.getName();
        }

        @Override
        public InetAddress address() {
            InetSocketAddress address = player.getAddress();
            return address == null ? null : address.getAddress();
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

    record SintesiCommand(SintesiCommands commands) implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            commands.execute(wrap(sender), args);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
            Audience audience = wrap(sender);
            return commands.hasPermission(audience) ? commands.suggest(audience, args) : List.of();
        }
    }
}
