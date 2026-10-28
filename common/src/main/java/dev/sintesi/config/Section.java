package dev.sintesi.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Section {

    private final String path;
    private final Map<?, ?> values;
    private final Set<String> read = new HashSet<>();

    public Section(String path, Object raw) {
        this.path = path;
        if (raw == null) {
            this.values = Map.of();
        } else if (raw instanceof Map<?, ?> map) {
            this.values = map;
        } else {
            throw new ConfigException(path + " must be a section");
        }
    }

    public Section section(String key) {
        read.add(key);
        return new Section(qualified(key), values.get(key));
    }

    public int integer(String key, int fallback, int min, int max) {
        read.add(key);
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new ConfigException(qualified(key) + " must be a number");
        }
        int result = number.intValue();
        if (result < min || result > max) {
            throw new ConfigException(qualified(key) + " must be between " + min + " and " + max);
        }
        return result;
    }

    public long longValue(String key, long fallback, long min, long max) {
        read.add(key);
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new ConfigException(qualified(key) + " must be a number");
        }
        long result = number.longValue();
        if (result < min || result > max) {
            throw new ConfigException(qualified(key) + " must be between " + min + " and " + max);
        }
        return result;
    }

    public double decimal(String key, double fallback, double min, double max) {
        read.add(key);
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new ConfigException(qualified(key) + " must be a number");
        }
        double result = number.doubleValue();
        if (result < min || result > max) {
            throw new ConfigException(qualified(key) + " must be between " + min + " and " + max);
        }
        return result;
    }

    public boolean bool(String key, boolean fallback) {
        read.add(key);
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean flag)) {
            throw new ConfigException(qualified(key) + " must be true or false");
        }
        return flag;
    }

    public String string(String key, String fallback) {
        read.add(key);
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new ConfigException(qualified(key) + " must be text");
        }
        return text;
    }

    public List<String> strings(String key) {
        read.add(key);
        Object value = values.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new ConfigException(qualified(key) + " must be a list");
        }
        return list.stream().map(String::valueOf).toList();
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public void rejectUnknown() {
        for (Object key : values.keySet()) {
            if (!read.contains(String.valueOf(key))) {
                throw new ConfigException("unknown key " + qualified(String.valueOf(key)));
            }
        }
    }

    private String qualified(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
