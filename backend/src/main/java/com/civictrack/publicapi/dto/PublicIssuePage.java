package com.civictrack.publicapi.dto;

import java.util.List;

/**
 * A page of the issue index, with the total so the screen can say how many
 * issues match rather than only how many it is showing.
 */
public record PublicIssuePage(List<PublicIssueDto> items, long total, int limit, int offset) {
}
