package com.civictrack.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The two windows in the verification loop.
 *
 * <p>The timeout is the one that carries an argument. Without it, an issue
 * reported by one person who then uninstalls the app never resolves, and the
 * department is punished on a public dashboard forever for a fix nobody
 * disputed. Fairness to staff is what makes the accountability half of the
 * system politically survivable.
 */
@ConfigurationProperties(prefix = "civictrack.verification")
public record VerificationProperties(

        /** Silence for this long, with no rejection, counts as consent. */
        @DefaultValue("72") int timeoutHours,

        /** Quiet days after resolution before an issue closes for good. */
        @DefaultValue("7") int autoCloseDays
) {
}
