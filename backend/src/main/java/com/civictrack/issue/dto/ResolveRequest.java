package com.civictrack.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The claim that work is finished.
 *
 * <p>Both fields are checked twice, and that is not redundancy for its own
 * sake. Bean validation gives the caller an immediate, field-level 400 for an
 * obviously malformed request; the transition guards enforce the same rules for
 * every caller of the state machine, including the ones that never pass through
 * this DTO. A rule that only exists on a request body is a rule the next
 * endpoint forgets.
 */
public record ResolveRequest(
        @NotBlank(message = "proofPhotoUrl is required") String proofPhotoUrl,
        @NotBlank(message = "note is required")
        @Size(min = 20, max = 2000, message = "note must be at least 20 characters") String note) {
}
