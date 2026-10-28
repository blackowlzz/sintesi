package dev.sintesi.paper;

import dev.sintesi.platform.Audience;
import dev.sintesi.platform.Platform;
import dev.sintesi.platform.PlatformPlayer;
import dev.sintesi.platform.SintesiCommands;
import dev.sintesi.text.Text;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
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

@SuppressWarnings("deprecation")
final class SpigotPlatform implements Platform, Listener {

    private static final int JSON_PROTOCOL = 765;
    private static final int NAMED_COLOR_PROTOCOL = 47;

    private final Map<UUID, Player> online = new ConcurrentHashMap<>();
    private final Server server;

    SpigotPlatform(Server server) {
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
    public Collection<SpigotPlayer> players() {
        return online.values().stream().map(SpigotPlayer::new).toList();
    }

    @Override
    public Optional<SpigotPlayer> player(String name) {
        return online.values().stream().filter(player -> player.getName().equalsIgnoreCase(name)).findFirst()
                .map(SpigotPlayer::new);
    }

    static BaseComponent[] components(String text, boolean console) {
        return links(ComponentSerializer.parse(Text.parse(text).toJson(console ? NAMED_COLOR_PROTOCOL : JSON_PROTOCOL)));
    }

    static BaseComponent[] links(BaseComponent[] parts) {
        for (BaseComponent part : parts) {
            link(part);
        }
        return parts;
    }

    private static void link(BaseComponent part) {
        if (part instanceof TextComponent text && text.getText().startsWith("https://")) {
            text.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, text.getText()));
        }
        if (part.getExtra() != null) {
            for (BaseComponent child : part.getExtra()) {
                link(child);
            }
        }
    }

    @Override
    public Audience console() {
        return new SpigotSource(server.getConsoleSender());
    }

    static Audience wrap(CommandSender sender) {
        return sender instanceof Player player ? new SpigotPlayer(player) : new SpigotSource(sender);
    }

    record SpigotSource(CommandSender sender) implements Audience {
        @Override
        public boolean hasPermission(String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        public void sendMessage(String text) {
            sender.spigot().sendMessage(components(text, true));
        }
    }

    record SpigotPlayer(Player player) implements PlatformPlayer {
        @Override
        public boolean hasPermission(String permission) {
            return player.hasPermission(permission);
        }

        @Override
        public void sendMessage(String text) {
            player.spigot().sendMessage(components(text, false));
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
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, components(text, false));
        }

        @Override
        public void showTitle(String title, String subtitle, int fadeInMillis, int stayMillis, int fadeOutMillis) {
            player.sendTitle(BaseComponent.toLegacyText(components(title, false)),
                    BaseComponent.toLegacyText(components(subtitle, false)), fadeInMillis / 50, stayMillis / 50,
                    fadeOutMillis / 50);
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
