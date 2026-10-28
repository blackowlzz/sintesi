package dev.sintesi.protocol;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;

public final class ProtocolBuffers {

    private ProtocolBuffers() {
    }

    public static int readVarInt(ByteBuf in) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (!in.isReadable()) {
                throw new DecodeException("truncated varint");
            }
            byte read = in.readByte();
            value |= (read & 0x7F) << (i * 7);
            if ((read & 0x80) == 0) {
                return value;
            }
        }
        throw new DecodeException("varint too long");
    }

    public static void writeVarInt(ByteBuf out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    public static String readString(ByteBuf in, int maxChars) {
        int length = readVarInt(in);
        if (length < 0 || length > maxChars * 4 || length > in.readableBytes()) {
            throw new DecodeException("bad string length");
        }
        String value = in.readCharSequence(length, StandardCharsets.UTF_8).toString();
        if (value.length() > maxChars) {
            throw new DecodeException("string too long");
        }
        return value;
    }

    public static void writeString(ByteBuf out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    public static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
