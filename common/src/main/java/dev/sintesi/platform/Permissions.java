package dev.sintesi.platform;

public final class Permissions {

    public static final String ADMIN = "sintesi.admin";
    public static final String COMMAND = "sintesi.command";
    public static final String NOTIFY = "sintesi.notify";
    public static final String UPDATE = "sintesi.update";

    private Permissions() {
    }

    public static String command(String subcommand) {
        return COMMAND + "." + subcommand;
    }

    public static boolean has(Audience source, String permission) {
        return source.hasPermission(ADMIN) || source.hasPermission(permission);
    }

    public static boolean canUse(Audience source, String subcommand) {
        return has(source, command(subcommand));
    }
}
