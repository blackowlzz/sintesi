package dev.sintesi.limbo;

import io.netty.buffer.ByteBuf;

public record Movement(boolean hasPosition, double x, double y, double z, boolean hasRotation, float yaw, float pitch,
        boolean onGround) {

    private static final float MAX_PITCH = 90f;

    public static Movement read(ByteBuf body, boolean withPosition, boolean withRotation) {
        double x = 0;
        double y = 0;
        double z = 0;
        float yaw = 0;
        float pitch = 0;
        if (withPosition) {
            x = body.readDouble();
            y = body.readDouble();
            z = body.readDouble();
        }
        if (withRotation) {
            yaw = body.readFloat();
            pitch = body.readFloat();
        }
        boolean onGround = (body.readUnsignedByte() & 1) != 0;
        return new Movement(withPosition, x, y, z, withRotation, yaw, pitch, onGround);
    }

    public boolean finite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    public boolean validRotation() {
        return !hasRotation || (Float.isFinite(yaw) && Float.isFinite(pitch) && Math.abs(pitch) <= MAX_PITCH);
    }
}
