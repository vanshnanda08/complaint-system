package com.civictrack.verification;

import com.civictrack.issue.policy.VerificationTally;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Section 4.3, plus DD-060, as a pure function. */
class VerificationTallyTest {

    @Test
    @DisplayName("required is ceil(reporters/2), floored at one and capped at three")
    void required() {
        assertThat(new VerificationTally(1, 0, 0, false).required()).isEqualTo(1);
        assertThat(new VerificationTally(3, 0, 0, false).required()).isEqualTo(2);
        assertThat(new VerificationTally(6, 0, 0, false).required()).isEqualTo(3);
        assertThat(new VerificationTally(40, 0, 0, false).required()).isEqualTo(3);
    }

    @Test
    @DisplayName("silence is consent only after the timeout, and only with no objection")
    void silence() {
        assertThat(new VerificationTally(1, 0, 0, false).quorumMet()).isFalse();
        VerificationTally silent = new VerificationTally(1, 0, 0, true);
        assertThat(silent.quorumMet()).isTrue();
        assertThat(silent.resolvedWithoutVerification()).isTrue();
    }

    @Test
    @DisplayName("DD-060: a contested vote the fix is winning resolves at the timeout; before it, it waits")
    void contestedMajority() {
        assertThat(new VerificationTally(6, 2, 1, false).quorumMet()).isFalse();
        VerificationTally atTimeout = new VerificationTally(6, 2, 1, true);
        assertThat(atTimeout.rejectionsPrevail()).isFalse();
        assertThat(atTimeout.quorumMet()).isTrue();
        assertThat(atTimeout.resolvedWithoutVerification()).isFalse();
    }

    @Test
    @DisplayName("every timed-out tally settles one way or the other -- none is left pending")
    void timeoutAlwaysSettles() {
        for (int conf = 0; conf <= 4; conf++) {
            for (int rej = 0; rej <= 4; rej++) {
                VerificationTally t = new VerificationTally(8, conf, rej, true);
                assertThat(t.quorumMet() || t.rejectionsPrevail())
                        .as("%d fixed, %d not fixed", conf, rej).isTrue();
            }
        }
    }
}
