package com.civictrack.common.error;

public class LowAccuracyException extends RuntimeException {

    private final double accuracyM;
    private final double maxAccuracyM;

    public LowAccuracyException(double accuracyM, double maxAccuracyM) {
        super("GPS accuracy %.1f m exceeds the maximum of %.1f m".formatted(accuracyM, maxAccuracyM));
        this.accuracyM = accuracyM;
        this.maxAccuracyM = maxAccuracyM;
    }

    public double getAccuracyM() {
        return accuracyM;
    }

    public double getMaxAccuracyM() {
        return maxAccuracyM;
    }
}
