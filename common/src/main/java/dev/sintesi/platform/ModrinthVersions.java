package dev.sintesi.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sintesi.config.UpdateCheckConfig.Channel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ModrinthVersions {

    record Entry(String id, String number, Channel channel) {
    }

    record Result(String current, Channel channel, Entry latest, int behind) {

        boolean outdated() {
            return latest != null;
        }
    }

    private ModrinthVersions() {
    }

    static List<Entry> parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("expected a list of versions");
        }
        JsonArray array = root.getAsJsonArray();
        List<Entry> entries = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject version = element.getAsJsonObject();
            String number = string(version, "version_number");
            String id = string(version, "id");
            String status = string(version, "status");
            Channel channel = channel(string(version, "version_type"));
            if (number == null || id == null || channel == null || status != null && !status.equals("listed")) {
                continue;
            }
            entries.add(new Entry(id, number, channel));
        }
        return entries;
    }

    static Result evaluate(String current, List<Entry> entries, Channel configured) {
        Channel effective = configured;
        if (configured == Channel.AUTO) {
            effective = isPrerelease(current) ? Channel.BETA : Channel.RELEASE;
            for (Entry entry : entries) {
                if (entry.number().equalsIgnoreCase(current)) {
                    effective = entry.channel();
                    break;
                }
            }
        }
        Entry latest = null;
        Set<String> newer = new HashSet<>();
        for (Entry entry : entries) {
            if (!effective.includes(entry.channel()) || compare(entry.number(), current) <= 0) {
                continue;
            }
            newer.add(entry.number().toLowerCase(Locale.ROOT));
            if (latest == null || compare(entry.number(), latest.number()) > 0) {
                latest = entry;
            }
        }
        return new Result(current, effective, latest, newer.size());
    }

    static int compare(String a, String b) {
        String[] left = split(a);
        String[] right = split(b);
        int core = compareCore(left[0], right[0]);
        if (core != 0) {
            return core;
        }
        if (left[1].isEmpty() || right[1].isEmpty()) {
            return Boolean.compare(left[1].isEmpty(), right[1].isEmpty());
        }
        return comparePrerelease(left[1], right[1]);
    }

    private static boolean isPrerelease(String version) {
        return !split(version)[1].isEmpty();
    }

    private static String[] split(String version) {
        String text = version.trim();
        if (text.startsWith("v") || text.startsWith("V")) {
            text = text.substring(1);
        }
        int build = text.indexOf('+');
        if (build >= 0) {
            text = text.substring(0, build);
        }
        int dash = text.indexOf('-');
        return dash < 0 ? new String[] {text, ""} : new String[] {text.substring(0, dash), text.substring(dash + 1)};
    }

    private static int compareCore(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long l = i < left.length ? number(left[i]) : 0;
            long r = i < right.length ? number(right[i]) : 0;
            if (l != r) {
                return Long.compare(l, r);
            }
        }
        return 0;
    }

    private static int comparePrerelease(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            boolean leftNumeric = left[i].matches("\\d{1,18}");
            boolean rightNumeric = right[i].matches("\\d{1,18}");
            int result;
            if (leftNumeric && rightNumeric) {
                result = Long.compare(Long.parseLong(left[i]), Long.parseLong(right[i]));
            } else if (leftNumeric != rightNumeric) {
                result = leftNumeric ? -1 : 1;
            } else {
                result = left[i].compareToIgnoreCase(right[i]);
            }
            if (result != 0) {
                return result;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static long number(String part) {
        int end = 0;
        while (end < part.length() && end < 18 && Character.isDigit(part.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Long.parseLong(part.substring(0, end));
    }

    private static Channel channel(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "release" -> Channel.RELEASE;
            case "beta" -> Channel.BETA;
            case "alpha" -> Channel.ALPHA;
            default -> null;
        };
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
