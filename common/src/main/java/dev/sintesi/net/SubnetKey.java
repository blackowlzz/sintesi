package dev.sintesi.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class SubnetKey {

    private static final long IPV4_TAG = 4L << 56;
    private static final long IPV6_TAG = 6L << 56;
    private static final long IPV4_ADDRESS_TAG = 5L << 56;
    private static final long IPV6_ADDRESS_TAG = 7L << 56;

    private SubnetKey() {
    }

    public static long of(InetAddress address) {
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            long value = ((raw[0] & 0xFFL) << 16) | ((raw[1] & 0xFFL) << 8) | (raw[2] & 0xFFL);
            return IPV4_TAG | value;
        }
        long value = 0;
        for (int i = 0; i < 6; i++) {
            value = (value << 8) | (raw[i] & 0xFFL);
        }
        return IPV6_TAG | value;
    }

    public static long address(InetAddress address) {
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            long value = ((raw[0] & 0xFFL) << 24) | ((raw[1] & 0xFFL) << 16) | ((raw[2] & 0xFFL) << 8) | (raw[3] & 0xFFL);
            return IPV4_ADDRESS_TAG | value;
        }
        long value = 0;
        for (int i = 0; i < 7; i++) {
            value = (value << 8) | (raw[i] & 0xFFL);
        }
        return IPV6_ADDRESS_TAG | value;
    }

    public static long address(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return address(inet.getAddress());
        }
        return 0L;
    }

    public static long of(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return of(inet.getAddress());
        }
        return 0L;
    }

    public static String describe(long key) {
        long tag = key & (0xFFL << 56);
        long value = key & ((1L << 56) - 1);
        if (tag == IPV4_ADDRESS_TAG) {
            return (value >>> 24 & 0xFF) + "." + (value >>> 16 & 0xFF) + "." + (value >>> 8 & 0xFF) + "." + (value & 0xFF);
        }
        if (tag == IPV4_TAG) {
            return (value >>> 16 & 0xFF) + "." + (value >>> 8 & 0xFF) + "." + (value & 0xFF) + ".0/24";
        }
        if (tag == IPV6_ADDRESS_TAG) {
            return hextets(value << 8, 4) + "::/56";
        }
        if (tag == IPV6_TAG) {
            return hextets(value << 16, 3) + "::/48";
        }
        return null;
    }

    private static String hextets(long bits, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(':');
            }
            out.append(Long.toHexString(bits >>> (48 - 16 * i) & 0xFFFF));
        }
        return out.toString();
    }

    public static long mix(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        return key;
    }
}
