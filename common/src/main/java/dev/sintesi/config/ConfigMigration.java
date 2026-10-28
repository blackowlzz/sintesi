package dev.sintesi.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigMigration {

    public record Result(String text, List<String> addedKeys) {
    }

    private ConfigMigration() {
    }

    public static Result migrate(String defaultsSource, String userSource) {
        Map<String, Object> defaults = MiniYaml.parse(defaultsSource);
        Map<String, Object> user = MiniYaml.parse(userSource);
        List<String> added = new ArrayList<>();
        Map<String, Object> merged = merge(defaults, user, "", added);
        if (added.isEmpty()) {
            return new Result(null, added);
        }
        StringBuilder out = new StringBuilder();
        emit(out, merged, 0);
        return new Result(out.toString(), added);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> defaults, Map<String, Object> user, String path,
            List<String> added) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            String qualified = path.isEmpty() ? key : path + "." + key;
            if (!user.containsKey(key)) {
                merged.put(key, entry.getValue());
                added.add(qualified);
            } else if (entry.getValue() instanceof Map<?, ?> nested && user.get(key) instanceof Map<?, ?> userNested) {
                merged.put(key, merge((Map<String, Object>) nested, (Map<String, Object>) userNested, qualified, added));
            } else {
                merged.put(key, user.get(key));
            }
        }
        for (Map.Entry<String, Object> entry : user.entrySet()) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    private static void emit(StringBuilder out, Map<?, ?> map, int indent) {
        String pad = " ".repeat(indent);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            out.append(pad).append(key(String.valueOf(entry.getKey()))).append(':');
            if (value instanceof Map<?, ?> nested) {
                if (nested.isEmpty()) {
                    out.append(" {}\n");
                } else {
                    out.append('\n');
                    emit(out, nested, indent + 2);
                }
            } else if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    out.append(" []\n");
                } else {
                    out.append('\n');
                    for (Object item : list) {
                        out.append(pad).append("  - ").append(scalar(item)).append('\n');
                    }
                }
            } else if (value == null) {
                out.append('\n');
            } else {
                out.append(' ').append(scalar(value)).append('\n');
            }
        }
    }

    private static String key(String key) {
        return key.matches("[A-Za-z0-9_.\\-]+") ? key : quote(key);
    }

    private static String scalar(Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        if (value instanceof Double number) {
            String plain = new BigDecimal(String.valueOf(number)).stripTrailingZeros().toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        String text = String.valueOf(value);
        boolean plainSafe = text.matches("[A-Za-z][A-Za-z0-9_.\\-]*") && !text.equals("true") && !text.equals("false")
                && !text.equals("null");
        return plainSafe ? text : quote(text);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
