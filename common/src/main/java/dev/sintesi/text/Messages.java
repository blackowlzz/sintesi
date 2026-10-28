package dev.sintesi.text;

import dev.sintesi.config.ConfigException;
import dev.sintesi.config.MiniYaml;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Messages {

    public static final List<String> LANGUAGES = List.of("en", "it");

    private final Map<String, String> values;
    private final List<String> unknownKeys;

    private Messages(Map<String, String> values, List<String> unknownKeys) {
        this.values = values;
        this.unknownKeys = List.copyOf(unknownKeys);
    }

    public static Messages defaults() {
        return defaults("en");
    }

    public static Messages defaults(String language) {
        return new Messages(flatten(MiniYaml.parse(resource(language))), List.of());
    }

    public static Messages load(String userSource) {
        return load(userSource, "en");
    }

    public static Messages load(String userSource, String language) {
        Map<String, String> values = flatten(MiniYaml.parse(resource(language)));
        Map<String, String> user = flatten(MiniYaml.parse(userSource));
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, String> entry : user.entrySet()) {
            if (values.containsKey(entry.getKey())) {
                values.put(entry.getKey(), entry.getValue());
            } else {
                unknown.add(entry.getKey());
            }
        }
        return new Messages(values, unknown);
    }

    public static String defaultSource() {
        return resource("en");
    }

    public static String defaultSource(String language) {
        return resource(language);
    }

    public java.util.Set<String> keys() {
        return java.util.Set.copyOf(values.keySet());
    }

    public List<String> unknownKeys() {
        return unknownKeys;
    }

    public String raw(String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing message " + key);
        }
        return value.replace("{prefix}", values.getOrDefault("prefix", ""));
    }

    public String format(String key, Object... placeholders) {
        return fill(raw(key), placeholders);
    }

    public static String fill(String template, Object... placeholders) {
        String result = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            result = result.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return result;
    }

    public Text text(String key, Object... placeholders) {
        return Text.parse(format(key, placeholders));
    }

    public Text passed() {
        return text("kick.passed");
    }

    public Text failed() {
        return text("kick.failed");
    }

    public Text busy() {
        return text("kick.busy");
    }

    public Text overloaded() {
        return text("kick.overloaded");
    }

    public Text floodBlocked(long minutes, String code) {
        return text("kick.flood-blocked", "minutes", minutes, "code", code);
    }

    public Text vpnBlocked() {
        return text("kick.vpn-blocked");
    }

    public Text tooManyAccounts() {
        return text("kick.too-many-accounts");
    }

    public Text unsupported() {
        return text("kick.unsupported");
    }

    private static Map<String, String> flatten(Map<String, Object> root) {
        Map<String, String> out = new HashMap<>();
        flatten("", root, out);
        return out;
    }

    private static void flatten(String path, Map<?, ?> map, Map<String, String> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = path.isEmpty() ? String.valueOf(entry.getKey()) : path + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, out);
            } else if (value instanceof List<?> list) {
                List<String> lines = new ArrayList<>();
                for (Object line : list) {
                    lines.add(line == null ? "" : String.valueOf(line));
                }
                out.put(key, String.join("\n", lines));
            } else if (value != null) {
                out.put(key, String.valueOf(value));
            } else {
                throw new ConfigException("messages: " + key + " has no value");
            }
        }
    }

    private static String resource(String language) {
        if (!LANGUAGES.contains(language)) {
            throw new IllegalArgumentException("unknown language " + language);
        }
        String path = language.equals("en") ? "/messages.yml" : "/messages_" + language + ".yml";
        try (InputStream stream = Messages.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("messages.yml missing from the jar");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
