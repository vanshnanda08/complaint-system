package com.civictrack.issue;

/**
 * Priority bands. Thresholds are on the band, not in the caller, so that
 * PriorityCalculator and any dashboard query agree by construction.
 */
public enum Priority {
    LOW, MEDIUM, HIGH, CRITICAL;

    public static Priority fromScore(double score) {
        if (score >= 80) return CRITICAL;
        if (score >= 50) return HIGH;
        if (score >= 25) return MEDIUM;
        return LOW;
    }

    /** True if {@code other} is a more urgent band than this one. */
    public boolean isLowerThan(Priority other) {
        return this.ordinal() < other.ordinal();
    }
}
