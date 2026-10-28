package dev.sintesi.text;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

public final class Text {

    public static final int NO_COLOR = -1;

    private static final int BOLD = 1;
    private static final int ITALIC = 2;
    private static final int UNDERLINED = 4;
    private static final int STRIKETHROUGH = 8;
    private static final int OBFUSCATED = 16;
    private static final int HEX_COLORS_SINCE = 735;

    private static final String[] NAMES = {
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
        "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
    };
    private static final int[] PALETTE = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    public record Span(String text, int color, int flags) {

        public boolean bold() {
            return (flags & BOLD) != 0;
        }
    }

    private final List<Span> spans;

    private Text(List<Span> spans) {
        this.spans = List.copyOf(spans);
    }

    public List<Span> spans() {
        return spans;
    }

    public static Text parse(String source) {
        List<Span> spans = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int color = NO_COLOR;
        int flags = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < source.length()) {
                char code = Character.toLowerCase(source.charAt(i + 1));
                int hex = code == '#' && i + 7 < source.length() ? hex(source, i + 2) : NO_COLOR;
                int legacy = "0123456789abcdef".indexOf(code);
                int flag = flagOf(code);
                if (hex != NO_COLOR || legacy >= 0 || flag != 0 || code == 'r') {
                    flush(spans, current, color, flags);
                    if (hex != NO_COLOR) {
                        color = hex;
                        flags = 0;
                        i += 7;
                    } else if (legacy >= 0) {
                        color = PALETTE[legacy];
                        flags = 0;
                        i += 1;
                    } else if (flag != 0) {
                        flags |= flag;
                        i += 1;
                    } else {
                        color = NO_COLOR;
                        flags = 0;
                        i += 1;
                    }
                    continue;
                }
            }
            current.append(c);
        }
        flush(spans, current, color, flags);
        return new Text(spans);
    }

    public String plain() {
        StringBuilder out = new StringBuilder();
        for (Span span : spans) {
            out.append(span.text());
        }
        return out.toString();
    }

    private boolean isPlain() {
        return spans.isEmpty() || (spans.size() == 1 && spans.get(0).color() == NO_COLOR && spans.get(0).flags() == 0);
    }

    public String toJson(int protocol) {
        if (isPlain()) {
            return quote(plain());
        }
        StringBuilder json = new StringBuilder("{\"text\":\"\",\"extra\":[");
        if (spans.isEmpty()) {
            json.append("\"\"");
        }
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"text\":").append(quote(span.text()));
            if (span.color() != NO_COLOR) {
                json.append(",\"color\":").append(quote(colorName(span.color(), protocol)));
            }
            appendFlag(json, "bold", span.flags(), BOLD);
            appendFlag(json, "italic", span.flags(), ITALIC);
            appendFlag(json, "underlined", span.flags(), UNDERLINED);
            appendFlag(json, "strikethrough", span.flags(), STRIKETHROUGH);
            appendFlag(json, "obfuscated", span.flags(), OBFUSCATED);
            json.append('}');
        }
        return json.append("]}").toString();
    }

    public byte[] toNbt(int protocol) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            if (isPlain()) {
                out.writeByte(8);
                out.writeUTF(plain());
                return bytes.toByteArray();
            }
            out.writeByte(10);
            out.writeByte(8);
            out.writeUTF("text");
            out.writeUTF("");
            out.writeByte(9);
            out.writeUTF("extra");
            out.writeByte(10);
            List<Span> written = spans.isEmpty() ? List.of(new Span("", NO_COLOR, 0)) : spans;
            out.writeInt(written.size());
            for (Span span : written) {
                out.writeByte(8);
                out.writeUTF("text");
                out.writeUTF(span.text());
                if (span.color() != NO_COLOR) {
                    out.writeByte(8);
                    out.writeUTF("color");
                    out.writeUTF(colorName(span.color(), protocol));
                }
                writeFlag(out, "bold", span.flags(), BOLD);
                writeFlag(out, "italic", span.flags(), ITALIC);
                writeFlag(out, "underlined", span.flags(), UNDERLINED);
                writeFlag(out, "strikethrough", span.flags(), STRIKETHROUGH);
                writeFlag(out, "obfuscated", span.flags(), OBFUSCATED);
                out.writeByte(0);
            }
            out.writeByte(0);
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    static String colorName(int rgb, int protocol) {
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i] == rgb) {
                return NAMES[i];
            }
        }
        if (protocol >= HEX_COLORS_SINCE) {
            return String.format("#%06X", rgb);
        }
        return NAMES[nearest(rgb)];
    }

    private static int nearest(int rgb) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            long dr = ((rgb >> 16) & 0xFF) - ((PALETTE[i] >> 16) & 0xFF);
            long dg = ((rgb >> 8) & 0xFF) - ((PALETTE[i] >> 8) & 0xFF);
            long db = (rgb & 0xFF) - (PALETTE[i] & 0xFF);
            long distance = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static void flush(List<Span> spans, StringBuilder current, int color, int flags) {
        if (current.length() > 0) {
            spans.add(new Span(current.toString(), color, flags));
            current.setLength(0);
        }
    }

    private static int flagOf(char code) {
        return switch (code) {
            case 'l' -> BOLD;
            case 'o' -> ITALIC;
            case 'n' -> UNDERLINED;
            case 'm' -> STRIKETHROUGH;
            case 'k' -> OBFUSCATED;
            default -> 0;
        };
    }

    private static int hex(String source, int start) {
        int value = 0;
        for (int i = start; i < start + 6; i++) {
            int digit = Character.digit(source.charAt(i), 16);
            if (digit < 0) {
                return NO_COLOR;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private static void appendFlag(StringBuilder json, String name, int flags, int flag) {
        if ((flags & flag) != 0) {
            json.append(",\"").append(name).append("\":true");
        }
    }

    private static void writeFlag(DataOutputStream out, String name, int flags, int flag) throws IOException {
        if ((flags & flag) != 0) {
            out.writeByte(1);
            out.writeUTF(name);
            out.writeByte(1);
        }
    }

    public static String quote(String text) {
        StringBuilder json = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
}
