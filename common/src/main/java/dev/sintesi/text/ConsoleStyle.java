package dev.sintesi.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ConsoleStyle {

    public static final String PREFIX = "&#FF8A1F&lSINTESI &8» ";
    private static final String BRAND = "&#FF8A1F";
    private static final String GOOD = "&#7CFC8A";
    private static final String BAD = "&#FF5C5C";
    private static final String NOTICE = "&#FFD447";
    private static final String PLAIN = "&7";
    private static final String[] LOGO = {
        "  _________.__        __                .__ ",
        " /   _____/|__| _____/  |_  ____   _____|__|",
        " \\_____  \\ |  |/    \\   __\\/ __ \\ /  ___/  |",
        " /        \\|  |   |  \\  | \\  ___/ \\___ \\|  |",
        "/_______  /|__|___|  /__|  \\___  >____  >__|",
        "        \\/         \\/          \\/     \\/    "
    };

    private ConsoleStyle() {
    }

    public static String line(String message) {
        return PREFIX + color(message) + message;
    }

    static String color(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.startsWith("attack") || lower.contains("wave of") || lower.contains("downloading")) {
            return NOTICE;
        }
        if (lower.contains("passed verification") || lower.contains("protection active")
                || lower.contains("shared through the database") || lower.contains("metrics available")) {
            return GOOD;
        }
        if (lower.contains("failed") || lower.contains("refused") || lower.contains("blocked")
                || lower.contains("could not") || lower.contains("not protected") || lower.contains("error")) {
            return BAD;
        }
        if (lower.contains("warning") || lower.contains("anyone who") || lower.contains("is empty")
                || lower.contains("not open yet") || lower.contains("never blocked") || lower.contains("forwarding")) {
            return NOTICE;
        }
        return PLAIN;
    }

    public static List<String> banner(String version, String platform, int protocols, String hook, String slogan) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        for (String row : LOGO) {
            lines.add(BRAND + row);
        }
        lines.add("");
        lines.add("&7  Version &f" + version + " &8| &7Platform &f" + platform + " &8| &7Protocols &f" + protocols);
        lines.add(GOOD + "  Sintesi protection active through " + hook + " for " + protocols + " protocols");
        lines.add("&8&o  " + slogan);
        lines.add("");
        return lines;
    }
}
