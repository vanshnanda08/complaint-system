package com.civictrack;

import com.civictrack.common.geo.GeoFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lat/lng canary, and the phase-1 proof that the spatial round trip works
 * end to end: a Java {@link Point} built by {@link GeoFactory} travels through
 * JDBC into PostGIS, is compared against a stored MultiPolygon, and resolves
 * to the ward a human would have named.
 *
 * <p>The project report rates a swapped latitude and longitude as a
 * high-likelihood, high-impact risk, and it is: the swap throws nothing,
 * corrupts nothing visibly, and surfaces as a ward lookup failing several
 * layers away from the mistake. A unit test on {@code GeoFactory} cannot catch
 * it, because both ordinates of a Ludhiana coordinate are valid in both
 * positions. Only a round trip against real geometry can, which is what this
 * class is.
 */
class WardContainmentIT extends IntegrationTestBase {

    // Ferozepur Road, near Bharat Nagar Chowk. Comfortably inside ward 1, not
    // near an edge, so the test asserts containment rather than tie-breaking.
    private static final double LUDHIANA_LAT = 30.9300;
    private static final double LUDHIANA_LNG = 75.8200;
    private static final int EXPECTED_WARD = 1;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a known Ludhiana coordinate falls inside the ward a human would name")
    void knownCoordinateResolvesToKnownWard() {
        Integer ward = wardNumberContaining(GeoFactory.point(LUDHIANA_LAT, LUDHIANA_LNG));

        assertThat(ward).isEqualTo(EXPECTED_WARD);
    }

    @Test
    @DisplayName("the same coordinate with lat and lng swapped falls in no ward at all")
    void swappedCoordinateResolvesToNothing() {
        // (30.93, 75.82) reversed is a point in the Indian Ocean, roughly off
        // the Somali coast. If a swap is ever introduced upstream, this is the
        // assertion that fails first and names the cause.
        Point swapped = GeoFactory.point(LUDHIANA_LNG, LUDHIANA_LAT);

        assertThat(wardNumberContaining(swapped))
                .as("a swapped coordinate must not resolve to a ward; if this passes, "
                    + "something upstream is silently reversing lat and lng")
                .isNull();
    }

    @Test
    @DisplayName("wards are disjoint, so no interior coordinate resolves to two of them")
    void wardsDoNotOverlap() {
        Integer overlapping = jdbc.queryForObject("""
                SELECT count(*) FROM wards a JOIN wards b ON a.id < b.id
                WHERE ST_Overlaps(a.boundary, b.boundary)
                """, Integer.class);

        assertThat(overlapping).isZero();
    }

    @Test
    @DisplayName("a coordinate outside the service area resolves to no ward")
    void coordinateOutsideServiceAreaResolvesToNothing() {
        // Delhi. Well outside the seeded Ludhiana grid, and the case that must
        // produce a clean "outside service area" rejection at ingest rather
        // than a null ward_id on an issue row.
        assertThat(wardNumberContaining(GeoFactory.point(28.6139, 77.2090))).isNull();
    }

    @Test
    @DisplayName("distances come back as geodesic metres, not degrees")
    void distanceIsGeodesicMetres() {
        // Two points 0.0002 degrees of longitude apart at Ludhiana's latitude.
        // Planar arithmetic would call this 0.0002 of something; the geography
        // cast calls it roughly 19 m, which is the number the merge radius is
        // actually compared against. Standing rule 2.
        Double metres = jdbc.queryForObject("""
                SELECT ST_Distance(
                    ST_SetSRID(ST_MakePoint(75.8570, 30.9010), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(75.8572, 30.9010), 4326)::geography)
                """, Double.class);

        assertThat(metres).isBetween(18.0, 20.0);
    }

    /** Returns the ward number containing the point, or null if none does. */
    private Integer wardNumberContaining(Point p) {
        return jdbc.query("""
                SELECT ward_number FROM wards
                WHERE ST_Contains(boundary, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                """,
                rs -> rs.next() ? rs.getInt(1) : null,
                GeoFactory.lngOf(p), GeoFactory.latOf(p));
    }
}
