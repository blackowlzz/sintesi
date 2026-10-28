package dev.sintesi.bungee;

import dev.sintesi.platform.Audience;
import dev.sintesi.platform.Platform;
import dev.sintesi.platform.PlatformPlayer;
import dev.sintesi.platform.SintesiCommands;
import dev.sintesi.text.Text;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.chat.ComponentSerializer;

final class BungeePlatform implements Platform {

    private static final int JSON_PROTOCOL = 775;
    private static final int NAMED_COLOR_PROTOCOL = 47;
    private static final int MILLIS_PER_TICK = 50;

    private final ProxyServer server;

    BungeePlatform(ProxyServer server) {
        this.server = server;
    }

    @Override
    public Collection<BungeePlayer> players() {
        return server.getPlayers().stream().map(player -> new BungeePlayer(server, player)).toList();
    }

    @Override
    public Optional<BungeePlayer> player(String name) {
        return Optional.ofNullable(server.getPlayer(name)).map(player -> new BungeePlayer(server, player));
    }

    static BaseComponent[] components(String text) {
        return links(ComponentSerializer.parse(Text.parse(text).toJson(JSON_PROTOCOL)));
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

    static BaseComponent[] namedColorComponents(String text) {
        return ComponentSerializer.parse(Text.parse(text).toJson(NAMED_COLOR_PROTOCOL));
    }

    Audience wrap(CommandSender sender) {
        return sender instanceof ProxiedPlayer player ? new BungeePlayer(server, player) : new BungeeSource(sender);
    }

    @Override
    public Audience console() {
        return new BungeeSource(server.getConsole());
    }

    record BungeeSource(CommandSender sender) implements Audience {
        @Override
        public boolean hasPermission(String permission) {
            return sender.hasPermission(permission);
        }

        @Override
        public void sendMessage(String text) {
            sender.sendMessage(namedColorComponents(text));
        }
    }

    record BungeePlayer(ProxyServer server, ProxiedPlayer player) implements PlatformPlayer {
        @Override
        public boolean hasPermission(String permission) {
            return player.hasPermission(permission);
        }

        @Override
        public void sendMessage(String text) {
            player.sendMessage(components(text));
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
            return player.getSocketAddress() instanceof InetSocketAddress inet ? inet.getAddress() : null;
        }

        @Override
        public void sendActionBar(String text) {
            player.sendMessage(ChatMessageType.ACTION_BAR, components(text));
        }

        @Override
        public void showTitle(String title, String subtitle, int fadeInMillis, int stayMillis, int fadeOutMillis) {
            server.createTitle()
                    .title(components(title))
                    .subTitle(components(subtitle))
                    .fadeIn(fadeInMillis / MILLIS_PER_TICK)
                    .stay(stayMillis / MILLIS_PER_TICK)
                    .fadeOut(fadeOutMillis / MILLIS_PER_TICK)
                    .send(player);
        }
    }

    static final class SintesiCommand extends Command implements TabExecutor {

        private final BungeePlatform platform;
        private final SintesiCommands commands;

        SintesiCommand(BungeePlatform platform, SintesiCommands commands) {
            super("sintesi", null, "antibot");
            this.platform = platform;
            this.commands = commands;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            commands.execute(platform.wrap(sender), args);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            Audience audience = platform.wrap(sender);
            return commands.hasPermission(audience) ? commands.suggest(audience, args) : List.of();
        }
    }
}
