package com.jenishsoni.jiratriage.duplicate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

public final class MinHash {

    private static final long PRIME = 2147483647L;
    private static final long SEED_STEP = 0x9e3779b97f4a7c15L;
    private final long[] multipliers;
    private final long[] offsets;

    public MinHash(int signatureSize, long seed) {
        if (signatureSize < 1) {
            throw new IllegalArgumentException("Signature size must be positive");
        }
        multipliers = new long[signatureSize];
        offsets = new long[signatureSize];
        for (int row = 0; row < signatureSize; row++) {
            multipliers[row] = 1 + Math.floorMod(mix64(seed + SEED_STEP * (2L * row + 1)), PRIME - 1);
            offsets[row] = Math.floorMod(mix64(seed + SEED_STEP * (2L * row + 2)), PRIME);
        }
    }

    public int[] signature(Set<String> shingles) {
        int[] signature = new int[multipliers.length];
        Arrays.fill(signature, Integer.MAX_VALUE);
        for (String shingle : shingles) {
            long value = hashShingle(shingle);
            for (int row = 0; row < signature.length; row++) {

                int permuted = (int) ((multipliers[row] * value + offsets[row]) % PRIME);
                signature[row] = Math.min(signature[row], permuted);
            }
        }
        return signature;
    }

    private static long hashShingle(String shingle) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : shingle.getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 0xff;
            hash *= 0x100000001b3L;
        }
        return Math.floorMod(hash, PRIME);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
