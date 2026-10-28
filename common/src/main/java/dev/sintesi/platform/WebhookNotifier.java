package dev.sintesi.platform;

import dev.sintesi.config.WebhookConfig;
import dev.sintesi.stats.Statistics.Snapshot;
import dev.sintesi.text.Placeholders;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class WebhookNotifier implements SintesiRuntime.Listener, AutoCloseable {

    private static final int MAX_CONTENT = 2000;

    private final SintesiRuntime runtime;
    private final Consumer<String> info;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sintesi-webhook");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    WebhookNotifier(SintesiRuntime runtime, Consumer<String> info) {
        this.runtime = runtime;
        this.info = info;
    }

    @Override
    public void attackStarted(Snapshot snapshot) {
        WebhookConfig config = runtime.core().config().webhook();
        if (config.enabled() && config.onAttackStart()) {
            send(config, runtime.messages().format("webhook.started", "cps", Placeholders.number(snapshot.cps())));
        }
    }

    @Override
    public void attackEnded(SintesiRuntime.AttackSummary summary) {
        WebhookConfig config = runtime.core().config().webhook();
        if (config.enabled() && config.onAttackEnd()) {
            send(config, runtime.messages().format("webhook.ended", Placeholders.summary(summary)));
        }
    }

    void send(WebhookConfig config, String content) {
        String text = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) : content;
        String body = "{\"username\":\"" + escape(config.username()) + "\",\"content\":\"" + escape(text)
                + "\",\"allowed_mentions\":{\"parse\":[]}}";
        executor.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.url())).timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json").header("User-Agent", "Sintesi")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 300) {
                    info.accept("Webhook answered HTTP " + response.statusCode());
                }
            } catch (Exception failure) {
                info.accept("Webhook could not be sent: " + failure.getMessage());
            }
        });
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
