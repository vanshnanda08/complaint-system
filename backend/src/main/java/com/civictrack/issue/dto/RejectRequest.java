package com.civictrack.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectRequest(
        @NotBlank(message = "reason is required")
        @Size(min = 20, max = 2000, message = "reason must be at least 20 characters") String reason) {
}
