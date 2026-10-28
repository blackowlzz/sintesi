package dev.sintesi.protocol;

import io.netty.buffer.ByteBuf;

public record Handshake(int protocol, String host, int port, int nextState) {

    public static final int PACKET_ID = 0x00;
    public static final int STATUS = 1;
    public static final int LOGIN = 2;
    public static final int TRANSFER = 3;
    public static final int MAX_HOST_LENGTH = 1000;
    private static final String FORWARDING_MARKER = "///";

    public static Handshake read(ByteBuf in) {
        int id = ProtocolBuffers.readVarInt(in);
        if (id != PACKET_ID) {
            throw new DecodeException("not a handshake");
        }
        int protocol = ProtocolBuffers.readVarInt(in);
        String host = ProtocolBuffers.readString(in, MAX_HOST_LENGTH);
        if (in.readableBytes() < 2) {
            throw new DecodeException("truncated handshake");
        }
        int port = in.readUnsignedShort();
        int nextState = ProtocolBuffers.readVarInt(in);
        if (in.isReadable()) {
            throw new DecodeException("trailing bytes");
        }
        return new Handshake(protocol, host, port, nextState);
    }

    public String address() {
        return addressOf(host);
    }

    public boolean forwardsAddress() {
        return host.contains(FORWARDING_MARKER);
    }

    public static String addressOf(String host) {
        int end = host.length();
        int nul = host.indexOf('\0');
        if (nul >= 0) {
            end = nul;
        }
        int marker = host.indexOf(FORWARDING_MARKER);
        if (marker >= 0 && marker < end) {
            end = marker;
        }
        return host.substring(0, end);
    }
}
