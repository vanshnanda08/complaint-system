package com.civictrack.issue.policy;

/**
 * Citizen votes on a fix, as the quorum rule needs them.
 *
 * <pre>
 *   required      = min(3, max(1, ceil(reporters / 2)))
 *   confirmations = verifications with verdict FIXED
 *   rejections    = verifications with verdict NOT_FIXED
 * </pre>
 *
 * <p>The cap at three is what keeps a widely reported issue resolvable: an
 * issue with forty reporters would otherwise need twenty votes, and the
 * verification loop would become a way for a busy street to keep a fixed
 * pothole open forever.
 *
 * <p>The floor at one is what keeps a single-reporter issue verifiable at all.
 * The case where that reporter is anonymous, and therefore cannot vote, is the
 * hole DD-006 records and measures rather than claims to have closed.
 */
public record VerificationTally(int reporters, int confirmations, int rejections,
                                boolean timeoutElapsed) {

    public int required() {
        return Math.min(3, Math.max(1, (int) Math.ceil(reporters / 2.0)));
    }

    /** Citizens confirmed, or nobody objected before the timeout. */
    public boolean quorumMet() {
        return confirmations >= required() || (timeoutElapsed && rejections == 0);
    }

    /** Resolved through silence rather than through a vote (DD-006). */
    public boolean resolvedWithoutVerification() {
        return confirmations == 0 && rejections == 0 && timeoutElapsed;
    }

    public boolean rejectionsPrevail() {
        return rejections >= 1 && rejections >= confirmations;
    }

    public static VerificationTally none(int reporters) {
        return new VerificationTally(reporters, 0, 0, false);
    }
}
