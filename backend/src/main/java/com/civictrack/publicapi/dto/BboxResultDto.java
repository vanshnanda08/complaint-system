package com.civictrack.publicapi.dto;

import java.util.List;

/**
 * Markers for one map viewport.
 *
 * <p>{@code capped} is the honest half of the 500-marker limit. A map that
 * quietly draws the first 500 of 4,000 issues is showing a filtered view of
 * the city while looking like a complete one, so the flag is returned and
 * blueprint 3.4 requires the screen to say "zoom in" rather than pretend.
 */
public record BboxResultDto(List<PublicIssueDto> items, int cap, boolean capped) {
}
