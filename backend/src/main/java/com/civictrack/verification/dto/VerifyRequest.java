package com.civictrack.verification.dto;

import com.civictrack.verification.Verdict;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One citizen's answer. The reason is optional and only kept for NOT_FIXED --
 * the screen offers it only there (blueprint 3.12), and a reason attached to
 * "fixed" has nobody to read it.
 */
public record VerifyRequest(
        @NotNull Verdict verdict,
        @Size(max = 280) String reason
) {
}
