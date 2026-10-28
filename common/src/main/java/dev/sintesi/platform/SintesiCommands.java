package dev.sintesi.platform;

import dev.sintesi.net.Blocklist;
import dev.sintesi.net.SubnetKey;
import dev.sintesi.text.Messages;
import dev.sintesi.text.Placeholders;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SintesiCommands {

    private static final List<String> SUBCOMMANDS = List.of("help", "stats", "verbose", "notify", "reload", "blocklist",
            "verified", "vpn", "version");
    private static final int DEFAULT_BLOCK_MINUTES = 60;

    private final Platform platform;
    private final SintesiRuntime runtime;
    private final StaffToggles toggles;
    private final String version;
    private final UpdateChecker updates;

    public SintesiCommands(Platform platform, SintesiRuntime runtime, StaffToggles toggles, String version,
            UpdateChecker updates) {
        this.platform = platform;
        this.runtime = runtime;
        this.toggles = toggles;
        this.version = version;
        this.updates = updates;
    }

    public boolean hasPermission(Audience source) {
        return Permissions.has(source, Permissions.COMMAND);
    }

    public void execute(Audience source, String[] args) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(subcommand)) {
            send(source, messages().format("command.unknown"));
            return;
        }
        if (!Permissions.canUse(source, subcommand)) {
            send(source, messages().format("command.no-permission"));
            return;
        }
        switch (subcommand) {
            case "help" -> help(source);
            case "stats" -> send(source, messages().format("stats", Placeholders.stats(runtime)));
            case "verbose" -> verbose(source);
            case "notify" -> notify(source);
            case "reload" -> reload(source);
            case "blocklist" -> blocklist(source, args);
            case "verified" -> verified(source, args);
            case "vpn" -> vpn(source, args);
            case "version" -> version(source);
            default -> send(source, messages().format("command.unknown"));
        }
    }

    public List<String> suggest(Audience source, String[] args) {
        if (args.length <= 1) {
            String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String subcommand : SUBCOMMANDS) {
                if (subcommand.startsWith(typed) && Permissions.canUse(source, subcommand)) {
                    out.add(subcommand);
                }
            }
            return out;
        }
        if (args.length == 2) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            List<String> options = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "blocklist" -> List.of("add", "remove", "info", "clear", "size");
                case "verified" -> List.of("count", "clear", "remove");
                case "vpn" -> List.of("status", "check", "reload");
                default -> List.of();
            };
            return options.stream().filter(option -> option.startsWith(typed)).toList();
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("verified") && args[1].equalsIgnoreCase("remove")
                || args[0].equalsIgnoreCase("vpn") && args[1].equalsIgnoreCase("check"))) {
            String typed = args[2].toLowerCase(Locale.ROOT);
            return platform.players().stream().map(PlatformPlayer::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        return List.of();
    }

    private void help(Audience source) {
        Messages messages = messages();
        StringBuilder out = new StringBuilder(messages.format("command.help-header", "version", version));
        for (String subcommand : SUBCOMMANDS) {
            if (Permissions.canUse(source, subcommand)) {
                out.append('\n').append(messages.format("command.help-line", "command", subcommand,
                        "description", messages.raw("description." + subcommand)));
            }
        }
        out.append('\n').append(messages.format("command.help-footer"));
        send(source, out.toString());
    }

    private void verbose(Audience source) {
        if (!(source instanceof PlatformPlayer player)) {
            send(source, messages().format("command.players-only"));
            return;
        }
        boolean enabled = toggles.toggleVerbose(player.uniqueId());
        send(source, messages().format(enabled ? "verbose.enabled" : "verbose.disabled"));
    }

    private void notify(Audience source) {
        if (!(source instanceof PlatformPlayer player)) {
            send(source, messages().format("command.players-only"));
            return;
        }
        boolean enabled = toggles.toggleNotify(player.uniqueId());
        send(source, messages().format(enabled ? "notify.enabled" : "notify.disabled"));
    }

    private void reload(Audience source) {
        try {
            runtime.reload();
            send(source, messages().format("command.reloaded"));
        } catch (Exception failure) {
            send(source, messages().format("command.reload-failed", "error", String.valueOf(failure.getMessage())));
        }
    }

    private void blocklist(Audience source, String[] args) {
        String action = args.length < 2 ? "help" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "help" -> {
                send(source, messages().format("command.blocklist-help"));
                send(source, messages().format("command.blocklist-size",
                        "count", Placeholders.number(runtime.blocklist().size())));
            }
            case "add" -> {
                if (args.length < 3) {
                    send(source, messages().format("command.usage", "usage", "blocklist add <ip> [minutes]"));
                    return;
                }
                InetAddress address = parse(source, args[2]);
                if (address == null) {
                    return;
                }
                int minutes = DEFAULT_BLOCK_MINUTES;
                if (args.length >= 4) {
                    try {
                        minutes = Math.max(1, Math.min(525_600, Integer.parseInt(args[3])));
                    } catch (NumberFormatException invalid) {
                        send(source, messages().format("command.usage", "usage", "blocklist add <ip> [minutes]"));
                        return;
                    }
                }
                runtime.blocklist().block(SubnetKey.address(address), minutes * 60_000L, Blocklist.Reason.MANUAL);
                send(source, messages().format("command.blocklist-added", "address", address.getHostAddress(),
                        "minutes", minutes));
            }
            case "remove" -> {
                if (args.length < 3) {
                    send(source, messages().format("command.usage", "usage", "blocklist remove <ip|code>"));
                    return;
                }
                Long coded = runtime.blocklist().keyOfCode(args[2]);
                if (coded != null) {
                    Blocklist.Entry entry = runtime.blocklist().entry(coded);
                    runtime.blocklist().unblock(coded);
                    send(source, messages().format("command.blocklist-removed", "address", addressOf(entry, args[2])));
                    return;
                }
                InetAddress address = parse(source, args[2]);
                if (address == null) {
                    return;
                }
                runtime.blocklist().unblock(SubnetKey.address(address));
                runtime.blocklist().unblock(SubnetKey.of(address));
                send(source, messages().format("command.blocklist-removed", "address", address.getHostAddress()));
            }
            case "info" -> {
                if (args.length < 3) {
                    send(source, messages().format("command.usage", "usage", "blocklist info <ip|code>"));
                    return;
                }
                Long coded = runtime.blocklist().keyOfCode(args[2]);
                long key;
                String display;
                if (coded != null) {
                    key = coded;
                    display = null;
                } else {
                    InetAddress address = parse(source, args[2]);
                    if (address == null) {
                        return;
                    }
                    key = SubnetKey.address(address);
                    display = address.getHostAddress();
                }
                Blocklist.Entry entry = runtime.blocklist().entry(key);
                if (entry == null) {
                    send(source, messages().format("command.blocklist-not-found", "target", args[2]));
                    return;
                }
                long minutes = Math.max(1, (entry.expiry() - runtime.clock().getAsLong() + 59_999) / 60_000);
                send(source, messages().format("command.blocklist-info", "address", display != null ? display : addressOf(entry, args[2]),
                        "minutes", minutes, "code", entry.code(),
                        "reason", messages().raw("command.blocklist-reason-" + entry.reason().name().toLowerCase(Locale.ROOT))));
            }
            case "clear" -> send(source, messages().format("command.blocklist-cleared",
                    "count", Placeholders.number(runtime.blocklist().clear())));
            case "size" -> send(source, messages().format("command.blocklist-size",
                    "count", Placeholders.number(runtime.blocklist().size())));
            default -> send(source, messages().format("command.blocklist-help"));
        }
    }

    private void version(Audience source) {
        if (updates == null || !updates.enabled()) {
            send(source, messages().format("command.version-disabled", "current", version));
            return;
        }
        if (updates.result() == null) {
            send(source, messages().format("command.version-checking"));
        }
        updates.request(result -> {
            if (result == null) {
                send(source, messages().format("command.version-unknown", "current", version));
            } else {
                send(source, messages().format(result.outdated() ? "command.version-outdated"
                        : "command.version-up-to-date", updates.placeholders(result)));
            }
        });
    }

    private void vpn(Audience source, String[] args) {
        String action = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> send(source, messages().format("command.vpn-status",
                    "mode", runtime.core().config().vpn().mode().name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    "ranges", Placeholders.number(runtime.vpnGuard().size()),
                    "refused", Placeholders.number(runtime.statistics().vpnRefusedTotal())));
            case "check" -> {
                if (args.length < 3) {
                    send(source, messages().format("command.usage", "usage", "vpn check <ip|player>"));
                    return;
                }
                InetAddress address = dev.sintesi.net.NumericAddress.parse(args[2]);
                String label;
                if (address != null) {
                    label = address.getHostAddress();
                } else {
                    java.util.Optional<? extends PlatformPlayer> online = platform.player(args[2]);
                    if (online.isEmpty() || online.get().address() == null) {
                        send(source, messages().format("command.vpn-unknown-target", "target", args[2]));
                        return;
                    }
                    address = online.get().address();
                    label = online.get().name() + " (" + address.getHostAddress() + ")";
                }
                send(source, messages().format(runtime.vpnGuard().blocks(address)
                        ? "command.vpn-listed" : "command.vpn-not-listed", "address", label));
            }
            case "reload" -> {
                runtime.refreshVpnLists();
                send(source, messages().format("command.vpn-reloading"));
            }
            default -> send(source, messages().format("command.usage", "usage", "vpn <status|check|reload>"));
        }
    }

    private void verified(Audience source, String[] args) {
        String action = args.length < 2 ? "count" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "count" -> send(source, messages().format("command.verified-count",
                    "count", Placeholders.number(runtime.store().size())));
            case "clear" -> send(source, messages().format("command.verified-cleared",
                    "count", Placeholders.number(runtime.store().clear())));
            case "remove" -> removeVerified(source, args);
            default -> send(source, messages().format("command.usage", "usage", "verified <count|clear|remove>"));
        }
    }

    private void removeVerified(Audience source, String[] args) {
        if (args.length < 3) {
            send(source, messages().format("command.usage", "usage", "verified remove <player> [ip]"));
            return;
        }
        String name = args[2];
        InetAddress address;
        if (args.length >= 4) {
            address = parse(source, args[3]);
            if (address == null) {
                return;
            }
        } else {
            java.util.Optional<? extends PlatformPlayer> online = platform.player(name);
            if (online.isEmpty()) {
                send(source, messages().format("command.player-offline", "name", name));
                return;
            }
            name = online.get().name();
            address = online.get().address();
        }
        boolean removed = runtime.store().remove(runtime.core().verifiedKey(name, SubnetKey.address(address)));
        send(source, messages().format(removed ? "command.verified-removed" : "command.verified-not-found", "name", name));
    }

    private InetAddress parse(Audience source, String text) {
        InetAddress address = dev.sintesi.net.NumericAddress.parse(text);
        if (address == null) {
            send(source, messages().format("command.invalid-address", "address", text));
        }
        return address;
    }

    private String addressOf(Blocklist.Entry entry, String fallback) {
        if (entry != null && entry.address() != null) {
            return entry.address();
        }
        return entry == null ? fallback : messages().raw("command.blocklist-unknown-address");
    }

    private Messages messages() {
        return runtime.messages();
    }

    private static void send(Audience source, String text) {
        source.sendMessage(text);
    }
}
