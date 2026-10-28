package dev.sintesi.limbo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public enum PlatformBlock {
    STONE(1.0),
    SLAB(0.5),
    CARPET(0.0625),
    BARRIER(1.0);

    private static final PlatformBlock[] VISIBLE = {STONE, SLAB, CARPET};

    private static final TreeMap<Integer, int[]> IDS = load();

    private final double height;

    PlatformBlock(double height) {
        this.height = height;
    }

    public static PlatformBlock[] visible() {
        return VISIBLE.clone();
    }

    public double height() {
        return height;
    }

    public int stateId(int protocol) {
        Map.Entry<Integer, int[]> row = IDS.floorEntry(protocol);
        if (row == null) {
            throw new IllegalArgumentException("no block ids for protocol " + protocol);
        }
        return row.getValue()[ordinal()];
    }

    public static PlatformBlock byStateId(int protocol, int stateId) {
        for (PlatformBlock block : values()) {
            if (block.stateId(protocol) == stateId) {
                return block;
            }
        }
        return null;
    }

    private static TreeMap<Integer, int[]> load() {
        TreeMap<Integer, int[]> table = new TreeMap<>();
        try (InputStream stream = PlatformBlock.class.getResourceAsStream("/limbo/blocks.txt")) {
            if (stream == null) {
                throw new IllegalStateException("limbo/blocks.txt missing");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split(" ");
                table.put(Integer.parseInt(parts[0]), new int[] {
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4])});
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return table;
    }
}
