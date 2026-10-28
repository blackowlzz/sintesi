package dev.sintesi.limbo;

import java.util.List;

public record LimboTemplate(byte[] joinGame, List<ConfigPacket> configuration, byte[] chunk, byte[] light) {

    public record ConfigPacket(String name, byte[] body) {
    }

    public boolean hasLight() {
        return light != null;
    }

    public byte[] joinGameFor(int entityId) {
        byte[] copy = joinGame.clone();
        copy[0] = (byte) (entityId >>> 24);
        copy[1] = (byte) (entityId >>> 16);
        copy[2] = (byte) (entityId >>> 8);
        copy[3] = (byte) entityId;
        return copy;
    }
}
