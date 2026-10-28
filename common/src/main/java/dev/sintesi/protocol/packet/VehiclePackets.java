package dev.sintesi.protocol.packet;

import dev.sintesi.protocol.ProtocolBuffers;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class VehiclePackets {

    private static final TreeMap<Integer, Integer> BOAT_TYPE = new TreeMap<>(Map.ofEntries(
            Map.entry(47, 1), Map.entry(477, 5), Map.entry(573, 6), Map.entry(755, 7), Map.entry(759, 8),
            Map.entry(762, 9), Map.entry(766, 10), Map.entry(768, 85), Map.entry(769, 84), Map.entry(771, 85),
            Map.entry(773, 87), Map.entry(774, 89), Map.entry(777, 90)));

    private VehiclePackets() {
    }

    public static int boatType(int protocol) {
        return BOAT_TYPE.floorEntry(protocol).getValue();
    }

    public static void spawnBoat(ByteBuf out, int protocol, int entityId, double x, double y, double z) {
        ProtocolBuffers.writeVarInt(out, entityId);
        if (protocol >= 107) {
            UUID uuid = UUID.randomUUID();
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
        }
        if (protocol >= 477) {
            ProtocolBuffers.writeVarInt(out, boatType(protocol));
        } else {
            out.writeByte(boatType(protocol));
        }
        if (protocol >= 107) {
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
        } else {
            out.writeInt((int) Math.floor(x * 32));
            out.writeInt((int) Math.floor(y * 32));
            out.writeInt((int) Math.floor(z * 32));
        }
        if (protocol >= 773) {
            out.writeByte(0);
        }
        out.writeByte(0);
        out.writeByte(0);
        if (protocol >= 759) {
            out.writeByte(0);
            ProtocolBuffers.writeVarInt(out, 0);
        } else {
            out.writeInt(0);
        }
        if (protocol >= 107 && protocol < 773) {
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(0);
        }
    }

    public static void mount(ByteBuf out, int protocol, int vehicleId, int passengerId) {
        if (protocol < 107) {
            out.writeInt(passengerId);
            out.writeInt(vehicleId);
            out.writeBoolean(false);
            return;
        }
        ProtocolBuffers.writeVarInt(out, vehicleId);
        ProtocolBuffers.writeVarInt(out, 1);
        ProtocolBuffers.writeVarInt(out, passengerId);
    }

    public static String mountPacket(int protocol) {
        return protocol < 107 ? "ATTACH_ENTITY" : "SET_PASSENGERS";
    }

    public static void destroy(ByteBuf out, int protocol, int entityId) {
        if (protocol != 755) {
            ProtocolBuffers.writeVarInt(out, 1);
        }
        ProtocolBuffers.writeVarInt(out, entityId);
    }
}
