package dev.sintesi.store;

public final class SipHash {

    private SipHash() {
    }

    public static long hash(long k0, long k1, byte[] data) {
        long v0 = 0x736f6d6570736575L ^ k0;
        long v1 = 0x646f72616e646f6dL ^ k1;
        long v2 = 0x6c7967656e657261L ^ k0;
        long v3 = 0x7465646279746573L ^ k1;
        int length = data.length;
        int blocks = length & ~7;
        for (int i = 0; i < blocks; i += 8) {
            long m = littleEndian(data, i, 8);
            v3 ^= m;
            for (int round = 0; round < 2; round++) {
                v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
                v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
                v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
                v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
            }
            v0 ^= m;
        }
        long last = ((long) length << 56) | littleEndian(data, blocks, length - blocks);
        v3 ^= last;
        for (int round = 0; round < 2; round++) {
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
        }
        v0 ^= last;
        v2 ^= 0xff;
        for (int round = 0; round < 4; round++) {
            v0 += v1; v1 = Long.rotateLeft(v1, 13); v1 ^= v0; v0 = Long.rotateLeft(v0, 32);
            v2 += v3; v3 = Long.rotateLeft(v3, 16); v3 ^= v2;
            v0 += v3; v3 = Long.rotateLeft(v3, 21); v3 ^= v0;
            v2 += v1; v1 = Long.rotateLeft(v1, 17); v1 ^= v2; v2 = Long.rotateLeft(v2, 32);
        }
        return v0 ^ v1 ^ v2 ^ v3;
    }

    private static long littleEndian(byte[] data, int offset, int count) {
        long value = 0;
        for (int i = count - 1; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }
}
