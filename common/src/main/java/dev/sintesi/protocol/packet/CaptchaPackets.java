package dev.sintesi.protocol.packet;

import dev.sintesi.protocol.ProtocolBuffers;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public final class CaptchaPackets {

    private static final int LEGACY_FILLED_MAP = 358;
    private static final int HOTBAR_START = 36;
    private static final TreeMap<Integer, Integer> FILLED_MAP = new TreeMap<>(Map.ofEntries(
            Map.entry(393, 608), Map.entry(401, 613), Map.entry(477, 671), Map.entry(735, 733), Map.entry(755, 847),
            Map.entry(759, 886), Map.entry(761, 914), Map.entry(762, 937), Map.entry(763, 941), Map.entry(765, 979),
            Map.entry(766, 982), Map.entry(768, 1022), Map.entry(769, 1031), Map.entry(770, 1042), Map.entry(771, 1059),
            Map.entry(773, 1104), Map.entry(775, 1105), Map.entry(776, 1133), Map.entry(777, 1238)));
    private static final TreeMap<Integer, Integer> MAP_ID_COMPONENT = new TreeMap<>(Map.ofEntries(
            Map.entry(766, 26), Map.entry(768, 36), Map.entry(770, 37), Map.entry(774, 44), Map.entry(775, 46),
            Map.entry(777, 48)));

    private CaptchaPackets() {
    }

    public static int filledMapItem(int protocol) {
        return protocol < 393 ? LEGACY_FILLED_MAP : FILLED_MAP.floorEntry(protocol).getValue();
    }

    public static int mapIdComponent(int protocol) {
        return MAP_ID_COMPONENT.floorEntry(protocol).getValue();
    }

    public static void mapData(ByteBuf out, int protocol, int mapId, byte[] pixels, int size) {
        ProtocolBuffers.writeVarInt(out, mapId);
        out.writeByte(0);
        if (protocol >= 107 && protocol < 755) {
            out.writeBoolean(false);
        }
        if (protocol >= 477) {
            out.writeBoolean(true);
        }
        if (protocol >= 755) {
            out.writeBoolean(false);
        } else {
            ProtocolBuffers.writeVarInt(out, 0);
        }
        out.writeByte(size);
        out.writeByte(size);
        out.writeByte(0);
        out.writeByte(0);
        ProtocolBuffers.writeVarInt(out, pixels.length);
        out.writeBytes(pixels);
    }

    public static void mapInHand(ByteBuf out, int protocol, int hotbarSlot, int mapId) {
        out.writeByte(0);
        if (protocol >= 756) {
            ProtocolBuffers.writeVarInt(out, 0);
        }
        out.writeShort(HOTBAR_START + hotbarSlot);
        int item = filledMapItem(protocol);
        if (protocol < 393) {
            out.writeShort(item);
            out.writeByte(1);
            out.writeShort(mapId);
            out.writeByte(0);
        } else if (protocol < 404) {
            out.writeShort(item);
            out.writeByte(1);
            mapNbt(out, mapId, true);
        } else if (protocol < 766) {
            out.writeBoolean(true);
            ProtocolBuffers.writeVarInt(out, item);
            out.writeByte(1);
            mapNbt(out, mapId, protocol < 764);
        } else {
            ProtocolBuffers.writeVarInt(out, 1);
            ProtocolBuffers.writeVarInt(out, item);
            ProtocolBuffers.writeVarInt(out, 1);
            ProtocolBuffers.writeVarInt(out, 0);
            ProtocolBuffers.writeVarInt(out, mapIdComponent(protocol));
            ProtocolBuffers.writeVarInt(out, mapId);
        }
    }

    private static void mapNbt(ByteBuf out, int mapId, boolean namedRoot) {
        out.writeByte(10);
        if (namedRoot) {
            out.writeShort(0);
        }
        out.writeByte(3);
        byte[] key = "map".getBytes(StandardCharsets.UTF_8);
        out.writeShort(key.length);
        out.writeBytes(key);
        out.writeInt(mapId);
        out.writeByte(0);
    }
}
