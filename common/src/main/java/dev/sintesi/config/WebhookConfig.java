package dev.sintesi.config;

public record WebhookConfig(String url, String username, boolean onAttackStart, boolean onAttackEnd) {

    public boolean enabled() {
        return !url.isEmpty();
    }
}
