package com.civictrack.moderation.dto;

import jakarta.validation.constraints.Size;

public record NoteRequest(@Size(max = 500) String note) {
}
