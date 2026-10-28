package dev.sintesi.protocol.packet;

import static dev.sintesi.protocol.Versions.V1_12_2;
import static dev.sintesi.protocol.Versions.V1_14;
import static dev.sintesi.protocol.Versions.V1_17;
import static dev.sintesi.protocol.Versions.V1_19_4;
import static dev.sintesi.protocol.Versions.V1_21_2;
import static dev.sintesi.protocol.Versions.V1_21_9;
import static dev.sintesi.protocol.Versions.V1_9;

import dev.sintesi.protocol.ProtocolBuffers;
import io.netty.buffer.ByteBuf;

public final class PlayPackets {

    public static final int RELATIVE_NONE = 0;

    private PlayPackets() {
    }

    public static void keepAlive(ByteBuf out, int protocol, long id) {
        if (protocol >= V1_12_2) {
            out.writeLong(id);
        } else {
            ProtocolBuffers.writeVarInt(out, (int) id);
        }
    }

    public static void positionAndLook(ByteBuf out, int protocol, double x, double y, double z,
            float yaw, float pitch, int teleportId) {
        if (protocol >= V1_21_2) {
            ProtocolBuffers.writeVarInt(out, teleportId);
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeDouble(0);
            out.writeDouble(0);
            out.writeDouble(0);
            out.writeFloat(yaw);
            out.writeFloat(pitch);
            out.writeInt(RELATIVE_NONE);
            return;
        }
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeFloat(yaw);
        out.writeFloat(pitch);
        out.writeByte(RELATIVE_NONE);
        if (protocol >= V1_9) {
            ProtocolBuffers.writeVarInt(out, teleportId);
        }
        if (protocol >= V1_17 && protocol < V1_19_4) {
            out.writeBoolean(false);
        }
    }

    public static void changeGameState(ByteBuf out, int reason, float value) {
        out.writeByte(reason);
        out.writeFloat(value);
    }

    public static void abilities(ByteBuf out, boolean invulnerable, boolean flying, boolean allowFlying,
            boolean creative, float flySpeed, float fovModifier) {
        int flags = (invulnerable ? 0x01 : 0) | (flying ? 0x02 : 0) | (allowFlying ? 0x04 : 0) | (creative ? 0x08 : 0);
        out.writeByte(flags);
        out.writeFloat(flySpeed);
        out.writeFloat(fovModifier);
    }

    public static void spawnPosition(ByteBuf out, int protocol, int x, int y, int z) {
        if (protocol >= V1_21_9) {
            ProtocolBuffers.writeString(out, "minecraft:overworld");
        }
        out.writeLong(packPosition(protocol, x, y, z));
        if (protocol >= V1_17) {
            out.writeFloat(0f);
        }
        if (protocol >= V1_21_9) {
            out.writeFloat(0f);
        }
    }

    public static long packPosition(int protocol, int x, int y, int z) {
        long px = (long) x & 0x3FFFFFFL;
        long pz = (long) z & 0x3FFFFFFL;
        long py = (long) y & 0xFFFL;
        if (protocol >= V1_14) {
            return (px << 38) | (pz << 12) | py;
        }
        return (px << 38) | (py << 26) | pz;
    }
}
