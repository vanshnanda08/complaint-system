package com.civictrack.moderation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record SplitRequest(@NotEmpty List<UUID> reportIds, @Size(max = 500) String note) {
}
