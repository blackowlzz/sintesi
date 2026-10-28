package dev.sintesi.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MiniYaml {

    private record Line(int number, int indent, String text) {
    }

    private final List<Line> lines = new ArrayList<>();
    private int position;

    private MiniYaml(String source) {
        String[] raw = source.replace("\r", "").split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String text = stripComment(raw[i]);
            if (text.isBlank()) {
                continue;
            }
            if (text.indexOf('\t') >= 0) {
                throw error(i + 1, "tabs are not allowed, use spaces");
            }
            int indent = 0;
            while (text.charAt(indent) == ' ') {
                indent++;
            }
            lines.add(new Line(i + 1, indent, text.strip()));
        }
    }

    public static Map<String, Object> parse(String source) {
        MiniYaml parser = new MiniYaml(source);
        if (parser.lines.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Line first = parser.lines.get(0);
        if (parser.lines.size() == 1 && first.text().equals("{}")) {
            return new LinkedHashMap<>();
        }
        if (first.indent() != 0) {
            throw error(first.number(), "unexpected indentation");
        }
        Map<String, Object> root = parser.map(0);
        if (parser.position < parser.lines.size()) {
            throw error(parser.lines.get(parser.position).number(), "unexpected indentation");
        }
        return root;
    }

    private Map<String, Object> map(int indent) {
        Map<String, Object> result = new LinkedHashMap<>();
        while (position < lines.size()) {
            Line line = lines.get(position);
            if (line.indent() < indent) {
                break;
            }
            if (line.indent() > indent) {
                throw error(line.number(), "unexpected indentation");
            }
            if (line.text().startsWith("- ") || line.text().equals("-")) {
                throw error(line.number(), "list item where a key was expected");
            }
            int colon = keySeparator(line.text());
            if (colon < 0) {
                throw error(line.number(), "expected key: value");
            }
            String key = unquote(line.text().substring(0, colon).strip(), line.number());
            String rest = line.text().substring(colon + 1).strip();
            if (key.isEmpty()) {
                throw error(line.number(), "empty key");
            }
            if (result.containsKey(key)) {
                throw error(line.number(), "duplicate key " + key);
            }
            position++;
            result.put(key, rest.isEmpty() ? nested(indent) : scalarOrInline(rest, line.number()));
        }
        return result;
    }

    private Object nested(int parentIndent) {
        if (position >= lines.size()) {
            return null;
        }
        Line next = lines.get(position);
        boolean listItem = next.text().startsWith("- ") || next.text().equals("-");
        if (listItem && next.indent() >= parentIndent) {
            return list(next.indent());
        }
        if (next.indent() <= parentIndent) {
            return null;
        }
        return map(next.indent());
    }

    private List<Object> list(int indent) {
        List<Object> result = new ArrayList<>();
        while (position < lines.size()) {
            Line line = lines.get(position);
            if (line.indent() != indent || !(line.text().startsWith("- ") || line.text().equals("-"))) {
                if (line.indent() > indent) {
                    throw error(line.number(), "unexpected indentation");
                }
                break;
            }
            String item = line.text().substring(1).strip();
            if (item.isEmpty()) {
                throw error(line.number(), "empty list item");
            }
            result.add(scalarOrInline(item, line.number()));
            position++;
        }
        return result;
    }

    private static Object scalarOrInline(String text, int number) {
        if (text.startsWith("[")) {
            if (!text.endsWith("]")) {
                throw error(number, "unclosed list");
            }
            String inner = text.substring(1, text.length() - 1).strip();
            List<Object> items = new ArrayList<>();
            if (!inner.isEmpty()) {
                for (String part : splitInline(inner, number)) {
                    items.add(scalar(part.strip(), number));
                }
            }
            return items;
        }
        if (text.startsWith("{")) {
            if (!text.equals("{}")) {
                throw error(number, "inline maps are not supported");
            }
            return new LinkedHashMap<String, Object>();
        }
        return scalar(text, number);
    }

    private static Object scalar(String text, int number) {
        if (text.isEmpty()) {
            throw error(number, "empty value");
        }
        char first = text.charAt(0);
        if (first == '"' || first == '\'') {
            return unquote(text, number);
        }
        switch (text) {
            case "true":
                return Boolean.TRUE;
            case "false":
                return Boolean.FALSE;
            case "null":
            case "~":
                return null;
            default:
                break;
        }
        String digits = text.replace("_", "");
        if (digits.matches("[+-]?\\d+")) {
            long value = Long.parseLong(digits);
            return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) (int) value : (Object) value;
        }
        if (digits.matches("[+-]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?")) {
            return Double.parseDouble(digits);
        }
        return text;
    }

    private static List<String> splitInline(String inner, int number) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quote != 0) {
            throw error(number, "unclosed quote");
        }
        parts.add(current.toString());
        return parts;
    }

    private static String unquote(String text, int number) {
        if (text.isEmpty()) {
            return text;
        }
        char quote = text.charAt(0);
        if (quote != '"' && quote != '\'') {
            return text;
        }
        if (text.length() < 2 || text.charAt(text.length() - 1) != quote) {
            throw error(number, "unclosed quote");
        }
        String body = text.substring(1, text.length() - 1);
        if (quote == '\'') {
            return body.replace("''", "'");
        }
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= body.length()) {
                throw error(number, "bad escape");
            }
            char escaped = body.charAt(i);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case '"', '\\' -> out.append(escaped);
                case 'u' -> {
                    if (i + 4 >= body.length()) {
                        throw error(number, "bad escape");
                    }
                    try {
                        out.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException bad) {
                        throw error(number, "bad escape");
                    }
                    i += 4;
                }
                default -> throw error(number, "bad escape");
            }
        }
        return out.toString();
    }

    private static int keySeparator(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':' && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ')) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static ConfigException error(int number, String message) {
        return new ConfigException("line " + number + ": " + message);
    }
}
