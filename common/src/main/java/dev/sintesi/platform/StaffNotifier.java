package dev.sintesi.platform;

import dev.sintesi.api.AttackState;
import dev.sintesi.platform.SintesiRuntime.AttackSummary;
import dev.sintesi.stats.Statistics.Snapshot;
import dev.sintesi.text.Messages;
import dev.sintesi.text.Placeholders;

public final class StaffNotifier implements SintesiRuntime.Listener {

    private static final int TITLE_REPEAT_SECONDS = 15;
    private static final int FADE_IN_MILLIS = 250;
    private static final int STAY_MILLIS = 3000;
    private static final int FADE_OUT_MILLIS = 750;

    private final Platform platform;
    private final SintesiRuntime runtime;
    private final StaffToggles toggles;
    private int secondsSinceTitle;

    public StaffNotifier(Platform platform, SintesiRuntime runtime, StaffToggles toggles) {
        this.platform = platform;
        this.runtime = runtime;
        this.toggles = toggles;
    }

    @Override
    public void second(Snapshot snapshot, AttackState state) {
        Messages messages = runtime.messages();
        Object[] values = Placeholders.live(messages, snapshot, state);
        String actionBar = null;
        for (PlatformPlayer player : platform.players()) {
            if (toggles.verbose(player.uniqueId()) && Permissions.canUse(player, "verbose")) {
                if (actionBar == null) {
                    actionBar = messages.format("verbose.actionbar", values);
                }
                player.sendActionBar(actionBar);
            }
        }
        if (state == AttackState.ATTACK && ++secondsSinceTitle >= TITLE_REPEAT_SECONDS) {
            showTitle(messages, values);
        }
    }

    @Override
    public void attackStarted(Snapshot snapshot) {
        Messages messages = runtime.messages();
        Object[] values = Placeholders.live(messages, snapshot, AttackState.ATTACK);
        showTitle(messages, values);
        String started = messages.format("notify.started", values);
        for (PlatformPlayer player : platform.players()) {
            if (receives(player)) {
                player.sendMessage(started);
            }
        }
    }

    @Override
    public void attackEnded(AttackSummary summary) {
        String ended = runtime.messages().format("notify.ended", Placeholders.summary(summary));
        for (PlatformPlayer player : platform.players()) {
            if (receives(player)) {
                player.sendMessage(ended);
            }
        }
    }

    private void showTitle(Messages messages, Object[] values) {
        secondsSinceTitle = 0;
        String title = messages.format("notify.title", values);
        String subtitle = messages.format("notify.subtitle", values);
        for (PlatformPlayer player : platform.players()) {
            if (receives(player)) {
                player.showTitle(title, subtitle, FADE_IN_MILLIS, STAY_MILLIS, FADE_OUT_MILLIS);
            }
        }
    }

    private boolean receives(PlatformPlayer player) {
        return Permissions.has(player, Permissions.NOTIFY) && toggles.notify(player.uniqueId());
    }
}
