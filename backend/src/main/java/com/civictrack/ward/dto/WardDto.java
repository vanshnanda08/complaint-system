package com.civictrack.ward.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.UUID;

/**
 * A ward, with its boundary only when the caller asked for it.
 *
 * <p>{@code boundary} is GeoJSON produced by {@code ST_AsGeoJSON} and emitted
 * raw, so the client receives a GeoJSON object rather than a string it has to
 * parse a second time.
 *
 * <p>It is omitted by default, and that default matters. Every screen in phase
 * 4 that touches wards wants a name for a filter dropdown; the only consumer
 * of the polygons is the ward detail screen, which is phase 7. Ludhiana's four
 * seeded MultiPolygons are already tens of kilobytes, and shipping them to
 * populate a {@code <select>} would be the single largest payload on the
 * issue index.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WardDto(
        UUID id,
        int wardNumber,
        String name,
        @JsonRawValue String boundary
) {
}
