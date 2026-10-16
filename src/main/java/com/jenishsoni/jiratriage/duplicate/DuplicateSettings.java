package com.jenishsoni.jiratriage.duplicate;

public record DuplicateSettings(int shingleSize, int signatureSize, int bands, long seed, double minimumSimilarity) {

    public DuplicateSettings {
        if (shingleSize < 1 || shingleSize > 10) {
            throw new IllegalArgumentException("shingle-size must be between 1 and 10");
        }
        if (signatureSize < 1 || signatureSize > 1024) {
            throw new IllegalArgumentException("signature-size must be between 1 and 1024");
        }
        if (bands < 1 || signatureSize % bands != 0) {
            throw new IllegalArgumentException("bands must be positive and divide signature-size exactly");
        }
        if (!Double.isFinite(minimumSimilarity) || minimumSimilarity <= 0 || minimumSimilarity > 1) {
            throw new IllegalArgumentException("minimum-similarity must be finite and in (0, 1]");
        }
    }

    public int rowsPerBand() {
        return signatureSize / bands;
    }
}
