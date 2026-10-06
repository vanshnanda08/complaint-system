package com.civictrack.moderation.dto;

import java.util.List;

public record ReviewPageDto(List<ReviewItemDto> items, long total, int limit, int offset) {
}
