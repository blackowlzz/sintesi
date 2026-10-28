package dev.sintesi.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StaffToggles {

    private static final String VERBOSE = "verbose-on";
    private static final String NOTIFY_OFF = "notify-off";

    private final Path file;
    private final Set<UUID> verbose = ConcurrentHashMap.newKeySet();
    private final Set<UUID> notifyOff = ConcurrentHashMap.newKeySet();

    public StaffToggles(Path file) {
        this.file = file;
        load();
    }

    public boolean verbose(UUID player) {
        return verbose.contains(player);
    }

    public boolean notify(UUID player) {
        return !notifyOff.contains(player);
    }

    public boolean toggleVerbose(UUID player) {
        boolean enabled = !verbose.remove(player) && verbose.add(player);
        save();
        return enabled;
    }

    public boolean toggleNotify(UUID player) {
        boolean enabled = notifyOff.remove(player) || !notifyOff.add(player);
        save();
        return enabled;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    UUID id = UUID.fromString(parts[1]);
                    if (parts[0].equals(VERBOSE)) {
                        verbose.add(id);
                    } else if (parts[0].equals(NOTIFY_OFF)) {
                        notifyOff.add(id);
                    }
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
            }
        } catch (IOException ignored) {
            return;
        }
    }

    private synchronized void save() {
        List<String> lines = new ArrayList<>();
        verbose.forEach(id -> lines.add(VERBOSE + " " + id));
        notifyOff.forEach(id -> lines.add(NOTIFY_OFF + " " + id));
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            return;
        }
    }
}
