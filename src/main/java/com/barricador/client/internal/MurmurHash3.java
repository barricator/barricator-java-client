package com.barricador.client.internal;

import java.nio.charset.StandardCharsets;

/**
 * MurmurHash3 (x86 32-bit) — byte-for-byte identical to the backend and the other SDKs so a user
 * buckets into the same variation everywhere. Bucketing hashes {@code "<flagKey>.<salt>.<value>"}.
 */
public final class MurmurHash3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private MurmurHash3() {
    }

    public static int hash32(byte[] data, int seed) {
        int h1 = seed;
        final int len = data.length;
        final int roundedEnd = len & 0xfffffffc;

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | (data[i + 3] << 24);
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        switch (len & 0x03) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
                // fallthrough
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
                // fallthrough
            case 1:
                k1 |= (data[roundedEnd] & 0xff);
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        h1 ^= len;
        h1 = fmix(h1);
        return h1;
    }

    private static int fmix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    private static int unsignedHash(String flagKey, String salt, String bucketBy) {
        String composite = flagKey + "." + (salt == null ? "" : salt) + "." + bucketBy;
        return hash32(composite.getBytes(StandardCharsets.UTF_8), 0);
    }

    /** Deterministic bucket in {@code [0, 100)} (per the Step 4 spec). */
    public static int bucket0to99(String flagKey, String salt, String bucketBy) {
        return Math.floorMod(unsignedHash(flagKey, salt, bucketBy), 100);
    }

    /** Deterministic bucket in {@code [0, 100000)} for sub-percent rollouts. */
    public static int bucket100k(String flagKey, String salt, String bucketBy) {
        return Math.floorMod(unsignedHash(flagKey, salt, bucketBy), 100_000);
    }
}
