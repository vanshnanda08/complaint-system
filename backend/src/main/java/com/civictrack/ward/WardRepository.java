package com.civictrack.ward;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WardRepository extends JpaRepository<Ward, UUID> {

    /**
     * Resolves a coordinate to the ward containing it, or empty if the point
     * falls outside the service area.
     *
     * <p>{@code ST_Contains} is strict about boundaries: a point exactly on a
     * shared edge belongs to neither polygon rather than both, so the result is
     * deterministic and a report on a boundary is rejected as outside the
     * service area rather than being assigned arbitrarily. LIMIT 1 is defensive
     * only; the seeded polygons are disjoint and WardContainmentIT asserts it.
     */
    @Query(value = """
            SELECT w.id FROM wards w
            WHERE ST_Contains(w.boundary, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
            LIMIT 1
            """, nativeQuery = true)
    Optional<UUID> findIdContaining(@Param("lat") double lat, @Param("lng") double lng);
}
