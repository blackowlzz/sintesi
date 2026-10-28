package dev.sintesi.protocol.packet;

import static dev.sintesi.protocol.Versions.V1_20_3;

import dev.sintesi.protocol.ProtocolBuffers;
import dev.sintesi.text.Text;
import io.netty.buffer.ByteBuf;

public final class DisconnectPackets {

    private DisconnectPackets() {
    }

    public static void login(ByteBuf out, int protocol, Text text) {
        ProtocolBuffers.writeString(out, text.toJson(protocol));
    }

    public static void playOrConfiguration(ByteBuf out, int protocol, Text text) {
        if (protocol >= V1_20_3) {
            out.writeBytes(text.toNbt(protocol));
        } else {
            login(out, protocol, text);
        }
    }

    public static String jsonText(String text) {
        return Text.quote(text);
    }
}
