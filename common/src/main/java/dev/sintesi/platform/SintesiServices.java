package dev.sintesi.platform;

import java.nio.file.Path;
import java.util.function.Consumer;

public record SintesiServices(SintesiRuntime runtime, SintesiCommands commands, Platform platform, String version) {

    public static final String SLOGAN =
            "We will glorify war, the world's only hygiene, against those who threaten the digital frontier";

    public static SintesiServices start(Path dataDirectory, Consumer<String> info, Platform platform, String version) {
        Consumer<String> console = message -> {
            try {
                platform.console().sendMessage(dev.sintesi.text.ConsoleStyle.line(message));
            } catch (RuntimeException | LinkageError failure) {
                info.accept(message);
            }
        };
        SintesiRuntime runtime = SintesiRuntime.start(dataDirectory, console);
        runtime.players(platform::players);
        runtime.addListener(new WebhookNotifier(runtime, console));
        StaffToggles toggles = new StaffToggles(dataDirectory.resolve("staff.txt"));
        runtime.addListener(new StaffNotifier(platform, runtime, toggles));
        UpdateChecker updates = new UpdateChecker(runtime, platform, version, console);
        updates.apply(runtime.core().config().updates());
        runtime.addListener(updates);
        runtime.onClose(updates);
        return new SintesiServices(runtime, new SintesiCommands(platform, runtime, toggles, version, updates), platform,
                version);
    }

    public void announce(String platformName, String hook) {
        for (String line : dev.sintesi.text.ConsoleStyle.banner(version, platformName,
                runtime.core().templates().protocols().size(), hook, SLOGAN)) {
            platform.console().sendMessage(line);
        }
    }
}
