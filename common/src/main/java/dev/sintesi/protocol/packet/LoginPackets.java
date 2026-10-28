package dev.sintesi.protocol.packet;

import static dev.sintesi.protocol.Versions.V1_16;
import static dev.sintesi.protocol.Versions.V1_19;
import static dev.sintesi.protocol.Versions.V1_20_5;
import static dev.sintesi.protocol.Versions.V1_21_2;
import static dev.sintesi.protocol.Versions.V26_2;

import dev.sintesi.protocol.ProtocolBuffers;
import io.netty.buffer.ByteBuf;
import java.util.UUID;

public final class LoginPackets {

    private LoginPackets() {
    }

    public static void loginSuccess(ByteBuf out, int protocol, UUID uuid, String name) {
        if (protocol >= V1_16) {
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
        } else {
            ProtocolBuffers.writeString(out, uuid.toString());
        }
        ProtocolBuffers.writeString(out, name);
        if (protocol >= V1_19) {
            ProtocolBuffers.writeVarInt(out, 0);
        }
        if (protocol >= V26_2) {
            out.writeLong(0L);
            out.writeLong(0L);
        }
        if (protocol >= V1_20_5 && protocol < V1_21_2) {
            out.writeBoolean(true);
        }
    }
}
