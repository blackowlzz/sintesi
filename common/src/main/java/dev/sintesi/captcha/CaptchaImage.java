package dev.sintesi.captcha;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

public final class CaptchaImage {

    public static final int SIZE = 128;
    public static final String ALPHABET = "ACDEFHJKLMNPRTUWXY3469";
    public static final int LENGTH = 5;

    private static final String HINT = "TYPE THE CODE IN CHAT";
    private static final int GLYPH_WIDTH = 5;
    private static final int GLYPH_HEIGHT = 7;
    private static final int[] LIGHT_BASES = {2, 3, 5, 8, 9, 14, 18, 20, 30, 31};
    private static final int[] DARK_BASES = {24, 25, 26, 27, 28, 29, 34, 35};
    private static final int CLUTTER_STROKES = 7;
    private static final int CURVES = 2;
    private static final Map<Character, String> FONT = font();

    private final String code;
    private final byte[] pixels;

    private CaptchaImage(String code, byte[] pixels) {
        this.code = code;
        this.pixels = pixels;
    }

    public String code() {
        return code;
    }

    public byte[] pixels() {
        return pixels.clone();
    }

    public boolean matches(String answer) {
        return answer != null && answer.trim().equalsIgnoreCase(code);
    }

    public static CaptchaImage random(SplittableRandom random) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        byte[] pixels = new byte[SIZE * SIZE];
        paintBackground(pixels, random);
        byte[] inks = new byte[4];
        for (int i = 0; i < inks.length; i++) {
            inks[i] = dark(random);
        }
        for (int i = 0; i < SIZE * SIZE / 16; i++) {
            pixels[random.nextInt(SIZE * SIZE)] = random.nextBoolean() ? inks[random.nextInt(inks.length)]
                    : light(random);
        }
        for (int i = 0; i < CLUTTER_STROKES; i++) {
            int x = random.nextInt(SIZE);
            int y = random.nextInt(12, 96);
            line(pixels, x, y, x + random.nextInt(-14, 15), y + random.nextInt(-14, 15), inks[random.nextInt(inks.length)]);
        }
        double waveX = random.nextDouble(0.6, 1.6);
        double waveY = random.nextDouble(0.5, 1.4);
        double periodX = random.nextDouble(14, 26);
        double periodY = random.nextDouble(16, 30);
        double phaseX = random.nextDouble(Math.PI * 2);
        double phaseY = random.nextDouble(Math.PI * 2);
        double step = random.nextDouble(22.5, 24);
        double x = (SIZE - step * (LENGTH - 1)) / 2;
        for (int i = 0; i < LENGTH; i++) {
            byte first = inks[random.nextInt(inks.length)];
            byte second = inks[random.nextInt(inks.length)];
            drawWarpedGlyph(pixels, code.charAt(i), x + random.nextDouble(-1.5, 1.5), 52 + random.nextDouble(-6, 6),
                    random.nextDouble(2.8, 3.2), random.nextDouble(3.6, 4.2), random.nextDouble(-0.26, 0.26),
                    random.nextDouble(-0.22, 0.22), random.nextDouble(0.71, 0.75), first, second, random,
                    waveX, waveY, periodX, periodY, phaseX, phaseY);
            x += step;
        }
        for (int i = 0; i < CURVES; i++) {
            drawCurve(pixels, random, inks[random.nextInt(inks.length)], i == 0);
        }
        int hintX = (SIZE - (HINT.length() * (GLYPH_WIDTH + 1) - 1)) / 2;
        for (int i = 0; i < HINT.length(); i++) {
            drawGlyph(pixels, HINT.charAt(i), hintX + i * (GLYPH_WIDTH + 1), SIZE - GLYPH_HEIGHT - 8, 1, shade(29, 3));
        }
        return new CaptchaImage(code.toString(), pixels);
    }

    private static void paintBackground(byte[] pixels, SplittableRandom random) {
        int cells = 5;
        double[] grid = new double[(cells + 1) * (cells + 1)];
        for (int i = 0; i < grid.length; i++) {
            grid[i] = random.nextDouble();
        }
        byte[] colors = {light(random), light(random), light(random)};
        double cell = (double) SIZE / cells;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double gx = x / cell;
                double gy = y / cell;
                int ix = (int) gx;
                int iy = (int) gy;
                double fx = gx - ix;
                double fy = gy - iy;
                double top = grid[iy * (cells + 1) + ix] * (1 - fx) + grid[iy * (cells + 1) + ix + 1] * fx;
                double bottom = grid[(iy + 1) * (cells + 1) + ix] * (1 - fx) + grid[(iy + 1) * (cells + 1) + ix + 1] * fx;
                double value = top * (1 - fy) + bottom * fy;
                pixels[y * SIZE + x] = colors[Math.min(colors.length - 1, (int) (value * colors.length))];
            }
        }
    }

    private static void drawWarpedGlyph(byte[] pixels, char c, double centerX, double centerY, double scaleX,
            double scaleY, double angle, double shear, double radius, byte first, byte second, SplittableRandom random,
            double waveX, double waveY, double periodX, double periodY, double phaseX, double phaseY) {
        String glyph = FONT.get(c);
        if (glyph == null) {
            return;
        }
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double limit = radius * radius;
        int reach = 24;
        for (int py = (int) centerY - reach; py <= (int) centerY + reach; py++) {
            for (int px = (int) centerX - reach; px <= (int) centerX + reach; px++) {
                double qx = px + waveX * Math.sin(py / periodX * Math.PI * 2 + phaseX) - centerX;
                double qy = py + waveY * Math.sin(px / periodY * Math.PI * 2 + phaseY) - centerY;
                double rx = cos * qx + sin * qy;
                double ry = -sin * qx + cos * qy;
                double u = (rx - shear * ry) / scaleX + GLYPH_WIDTH / 2.0;
                double v = ry / scaleY + GLYPH_HEIGHT / 2.0;
                if (inkAt(glyph, u, v, limit)) {
                    set(pixels, px, py, random.nextInt(3) == 0 ? second : first);
                }
            }
        }
    }

    private static boolean inkAt(String glyph, double u, double v, double limit) {
        int column = (int) Math.floor(u);
        int row = (int) Math.floor(v);
        for (int r = row - 1; r <= row + 1; r++) {
            for (int col = column - 1; col <= column + 1; col++) {
                if (r < 0 || col < 0 || r >= GLYPH_HEIGHT || col >= GLYPH_WIDTH
                        || glyph.charAt(r * GLYPH_WIDTH + col) != '1') {
                    continue;
                }
                double du = u - (col + 0.5);
                double dv = v - (r + 0.5);
                if (du * du + dv * dv <= limit) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void drawCurve(byte[] pixels, SplittableRandom random, byte ink, boolean thick) {
        double baseY = random.nextDouble(36, 70);
        double amplitude = random.nextDouble(6, 16);
        double period = random.nextDouble(40, 110);
        double phase = random.nextDouble(Math.PI * 2);
        double slope = random.nextDouble(-0.2, 0.2);
        int start = random.nextInt(0, 18);
        int end = random.nextInt(110, SIZE);
        for (int x = start; x <= end; x++) {
            int y = (int) Math.round(baseY + slope * (x - 64) + amplitude * Math.sin(x / period * Math.PI * 2 + phase));
            set(pixels, x, y, ink);
            if (thick) {
                set(pixels, x, y + 1, ink);
            }
        }
    }

    private static byte light(SplittableRandom random) {
        return shade(LIGHT_BASES[random.nextInt(LIGHT_BASES.length)], 2);
    }

    private static byte dark(SplittableRandom random) {
        return shade(DARK_BASES[random.nextInt(DARK_BASES.length)], 3);
    }

    static String glyphFor(char c) {
        return FONT.get(c);
    }

    private static void drawGlyph(byte[] pixels, char c, int left, int top, int scale, byte ink) {
        String glyph = FONT.get(c);
        if (glyph == null) {
            return;
        }
        for (int row = 0; row < GLYPH_HEIGHT; row++) {
            for (int column = 0; column < GLYPH_WIDTH; column++) {
                if (glyph.charAt(row * GLYPH_WIDTH + column) != '1') {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        set(pixels, left + column * scale + dx, top + row * scale + dy, ink);
                    }
                }
            }
        }
    }

    private static void line(byte[] pixels, int x0, int y0, int x1, int y1, byte ink) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            set(pixels, x0, y0, ink);
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int doubled = 2 * error;
            if (doubled >= dy) {
                error += dy;
                x0 += sx;
            }
            if (doubled <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    private static void set(byte[] pixels, int x, int y, byte color) {
        if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) {
            pixels[y * SIZE + x] = color;
        }
    }

    private static byte shade(int baseColor, int shade) {
        return (byte) (baseColor * 4 + shade);
    }

    private static Map<Character, String> font() {
        String[] glyphs = {
            "A01110100011000111111100011000110001", "B11110100011000111110100011000111110",
            "C01110100011000010000100001000101110", "D11110100011000110001100011000111110",
            "E11111100001000011110100001000011111", "F11111100001000011110100001000010000",
            "G01110100011000010111100011000101111", "H10001100011000111111100011000110001",
            "I01110001000010000100001000010001110", "J00111000100001000010000101001001100",
            "K10001100101010011000101001001010001", "L10000100001000010000100001000011111",
            "M10001110111010110101100011000110001", "N10001100011100110101100111000110001",
            "O01110100011000110001100011000101110", "P11110100011000111110100001000010000",
            "Q01110100011000110001101011001001101", "R11110100011000111110101001001010001",
            "S01111100001000001110000010000111110", "T11111001000010000100001000010000100",
            "U10001100011000110001100011000101110", "V10001100011000110001100010101000100",
            "W10001100011000110101101011010101010", "X10001100010101000100010101000110001",
            "Y10001100011000101010001000010000100", "Z11111000010001000100010001000011111",
            "001110100011001110101110011000101110", "100100011000010000100001000010001110",
            "201110100010000100010001000100011111", "311111000100010000010000011000101110",
            "400010001100101010010111110001000010", "511111100001111000001000011000101110",
            "600110010001000011110100011000101110", "711111000010001000100010000100001000",
            "801110100011000101110100011000101110", "901110100011000101111000010001001100"
        };
        Map<Character, String> font = new HashMap<>();
        for (String glyph : glyphs) {
            font.put(glyph.charAt(0), glyph.substring(1));
        }
        return Map.copyOf(font);
    }
}
