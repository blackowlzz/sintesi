package dev.sintesi.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PacketIds {

    public enum State {
        STATUS,
        LOGIN,
        CONFIGURATION,
        PLAY
    }

    public enum Side {
        TO_SERVER,
        TO_CLIENT
    }

    private record Entry(String name, int[] protocols, int[] ids) {
        int idFor(int protocol) {
            int result = -1;
            for (int i = 0; i < protocols.length && protocols[i] <= protocol; i++) {
                result = ids[i];
            }
            return result;
        }
    }

    private static final int REVERSE_SIZE = 256;

    private final Map<String, Entry> byName = new HashMap<>();
    private final Map<String, Entry[]> byGroup = new HashMap<>();
    private final Map<String, String[]> reverseCache = new ConcurrentHashMap<>();

    private PacketIds() {
    }

    public static PacketIds load() {
        try (InputStream stream = PacketIds.class.getResourceAsStream("/packet-ids.txt")) {
            if (stream == null) {
                throw new IllegalStateException("packet-ids.txt missing");
            }
            return parse(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static PacketIds parse(BufferedReader reader) throws IOException {
        PacketIds ids = new PacketIds();
        Map<String, java.util.List<Entry>> groups = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(" ");
            int pairs = parts.length - 3;
            int[] protocols = new int[pairs];
            int[] values = new int[pairs];
            for (int i = 0; i < pairs; i++) {
                String[] pair = parts[i + 3].split("=");
                protocols[i] = Integer.parseInt(pair[0]);
                values[i] = Integer.parseInt(pair[1]);
            }
            Entry entry = new Entry(parts[2], protocols, values);
            String group = parts[0] + " " + parts[1];
            ids.byName.put(group + " " + parts[2], entry);
            groups.computeIfAbsent(group, key -> new java.util.ArrayList<>()).add(entry);
        }
        groups.forEach((group, list) -> ids.byGroup.put(group, list.toArray(new Entry[0])));
        return ids;
    }

    public int id(State state, Side side, String name, int protocol) {
        Entry entry = byName.get(state + " " + side + " " + name);
        if (entry == null) {
            throw new IllegalArgumentException(state + " " + side + " " + name);
        }
        return entry.idFor(protocol);
    }

    public String name(State state, Side side, int protocol, int id) {
        String group = state + " " + side;
        String[] reverse = reverseCache.computeIfAbsent(group + " " + protocol, key -> {
            String[] map = new String[REVERSE_SIZE];
            Entry[] entries = byGroup.get(group);
            if (entries != null) {
                for (Entry entry : entries) {
                    int value = entry.idFor(protocol);
                    if (value >= 0 && value < REVERSE_SIZE) {
                        map[value] = entry.name();
                    }
                }
            }
            return map;
        });
        return id >= 0 && id < REVERSE_SIZE ? reverse[id] : null;
    }
}
