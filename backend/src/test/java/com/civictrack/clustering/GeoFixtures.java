package com.civictrack.clustering;

/**
 * Shared coordinates for clustering tests.
 *
 * <p>Choosing these was not arbitrary and the constraints are worth recording,
 * because a fixture that violates either one produces a test that fails for a
 * reason unrelated to what it is testing.
 *
 * <p><b>Ward interior.</b> The seeded wards are a 2x2 grid, and clustering
 * never crosses a ward boundary. A fixture near a boundary would make
 * "these two reports merged" depend on which side of a line they landed. This
 * origin is over 3.2 km from the nearest ward edge, so every offset used here
 * stays comfortably inside ward 1.
 *
 * <p><b>Advisory-lock cell centre.</b> The concurrency control snaps to a
 * 0.002-degree grid, and this point is a cell <em>centre</em> -- roughly 95 m
 * from the nearest cell edge in longitude and 111 m in latitude. A fixture on a
 * cell boundary would let concurrent reports of the same coordinate take
 * different locks and race, which would make the concurrency test flaky in a
 * way that looks exactly like the bug it exists to catch.
 *
 * <p>Note that the demo coordinate from the specification, (30.900965,
 * 75.857277), satisfies neither: it sits 107 m from a ward boundary. It is
 * fine on a projector and unusable as a fixture.
 */
public final class GeoFixtures {

    public static final double LAT = 30.9300;
    public static final double LNG = 75.8200;

    /** Metres per degree of latitude. Constant enough at any latitude. */
    private static final double M_PER_DEG_LAT = 111_320.0;

    /** Metres per degree of longitude at this latitude (cos 30.93 ~ 0.858). */
    private static final double M_PER_DEG_LNG = 95_545.0;

    private GeoFixtures() {
    }

    /** A latitude offset by the given number of metres north. */
    public static double latOffsetM(double metres) {
        return LAT + metres / M_PER_DEG_LAT;
    }

    /** A longitude offset by the given number of metres east. */
    public static double lngOffsetM(double metres) {
        return LNG + metres / M_PER_DEG_LNG;
    }
}
