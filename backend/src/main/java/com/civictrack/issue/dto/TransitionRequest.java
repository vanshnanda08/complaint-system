package com.civictrack.issue.dto;

import jakarta.validation.constraints.Size;

/** A transition that needs nothing but an optional note. */
public record TransitionRequest(@Size(max = 2000) String note) {
}
