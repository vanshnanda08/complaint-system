package com.civictrack.issue.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AssignRequest(
        @NotNull(message = "assigneeId is required") UUID assigneeId,
        @Size(max = 2000) String note) {
}
