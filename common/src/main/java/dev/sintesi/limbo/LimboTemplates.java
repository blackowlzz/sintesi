package dev.sintesi.limbo;

import dev.sintesi.limbo.LimboTemplate.ConfigPacket;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class LimboTemplates {

    private static final int FORMAT = 1;

    private final Map<Integer, String> fileByProtocol = new HashMap<>();
    private final Map<String, LimboTemplate> parsed = new HashMap<>();

    private LimboTemplates() {
    }

    public static LimboTemplates load() {
        LimboTemplates templates = new LimboTemplates();
        try (InputStream stream = resource("index.txt")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    String[] parts = line.split(" ");
                    templates.fileByProtocol.put(Integer.parseInt(parts[0]), parts[1]);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return templates;
    }

    public boolean supports(int protocol) {
        return fileByProtocol.containsKey(protocol);
    }

    public Set<Integer> protocols() {
        return new TreeSet<>(fileByProtocol.keySet());
    }

    public synchronized LimboTemplate forProtocol(int protocol) {
        String file = fileByProtocol.get(protocol);
        if (file == null) {
            throw new IllegalArgumentException("unsupported protocol " + protocol);
        }
        return parsed.computeIfAbsent(file, LimboTemplates::read);
    }

    private static InputStream resource(String name) {
        InputStream stream = LimboTemplates.class.getResourceAsStream("/limbo/" + name);
        if (stream == null) {
            throw new IllegalStateException("limbo resource missing: " + name);
        }
        return stream;
    }

    private static LimboTemplate read(String file) {
        try (DataInputStream in = new DataInputStream(resource(file + ".tpl"))) {
            if (in.readUnsignedByte() != FORMAT) {
                throw new IllegalStateException("template format");
            }
            byte[] join = readBlock(in);
            int configCount = in.readUnsignedShort();
            List<ConfigPacket> config = new ArrayList<>(configCount);
            for (int i = 0; i < configCount; i++) {
                byte[] name = new byte[in.readUnsignedByte()];
                in.readFully(name);
                config.add(new ConfigPacket(new String(name, StandardCharsets.UTF_8), readBlock(in)));
            }
            byte[] chunk = readBlock(in);
            byte[] light = in.readUnsignedByte() == 1 ? readBlock(in) : null;
            return new LimboTemplate(join, List.copyOf(config), chunk, light);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] readBlock(DataInputStream in) throws IOException {
        byte[] block = new byte[in.readInt()];
        in.readFully(block);
        return block;
    }
}
