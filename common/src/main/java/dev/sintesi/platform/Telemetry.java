package dev.sintesi.platform;

import dev.faststats.ErrorTracker;

public final class Telemetry {

    public static final String TOKEN = "80d545ae531a720f3a8c73bfe47fd0b7";

    private static final String IPV4 = "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b";
    private static final String IPV6 = "\\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\\b";

    private Telemetry() {
    }

    public static ErrorTracker errorTracker() {
        return ErrorTracker.contextAware(Telemetry.class.getClassLoader())
                .anonymize(IPV4, "<address>")
                .anonymize(IPV6, "<address>");
    }
}
