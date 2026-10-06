package com.civictrack.moderation.dto;

/** Both sides of a split, before it is made: what stays, and the issue that would be created. */
public record SplitPreviewDto(ClusterGeometryDto remaining, ClusterGeometryDto created) {
}
