package com.civictrack.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecategoriseRequest(@NotBlank String categoryCode, @Size(max = 500) String note) {
}
