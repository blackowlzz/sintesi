package dev.sintesi.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class Chat {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .extractUrls()
            .build();

    private Chat() {
    }

    static Component of(String text) {
        return SERIALIZER.deserialize(text);
    }
}
