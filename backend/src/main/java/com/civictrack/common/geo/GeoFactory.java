package com.civictrack.common.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * The one and only place in this codebase where a JTS {@link Point} is
 * constructed from a latitude and a longitude.
 *
 * <p>JTS orders coordinates {@code (x, y)}, which for SRID 4326 means
 * {@code (longitude, latitude)} — the reverse of how humans, GPS readouts and
 * the Geolocation API say it. Every geographic bug in a project like this is
 * ultimately a swapped lat/lng, and the bug is invisible: a Ludhiana report at
 * (30.9, 75.86) instead of (75.86, 30.9) lands in the Indian Ocean off Somalia
 * and simply fails the ward lookup, several layers away from the swap.
 *
 * <p>So the constructor is written once, here, and nothing else in the
 * application calls {@code GeometryFactory.createPoint} directly. Standing
 * rule 3. If you find yourself wanting to build a Point elsewhere, add a
 * method to this class instead.
 *
 * <p>Note what this class deliberately does <em>not</em> do: it never computes
 * a distance. Planar distance between two SRID 4326 points is in degrees, and
 * a degree of longitude at Ludhiana's latitude is ~96 km against ~111 km for a
 * degree of latitude, so any planar metre figure would be silently wrong by a
 * latitude-dependent factor. All distances in this system are computed by
 * PostGIS as {@code ST_Distance(a::geography, b::geography)}, which is true
 * geodesic metres. Standing rule 2.
 */
public final class GeoFactory {

    /** WGS 84. The SRID of every geometry column in the schema. */
    public static final int SRID_WGS84 = 4326;

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING), SRID_WGS84);

    private GeoFactory() {
    }

    /**
     * Builds a WGS 84 point.
     *
     * <p>Arguments are in human order — latitude first, longitude second —
     * precisely because that is the order every caller already has them in.
     * The swap into JTS {@code (x, y)} order happens once, below.
     *
     * @param lat latitude in degrees, -90 to 90
     * @param lng longitude in degrees, -180 to 180
     * @throws IllegalArgumentException if either value is out of range, which
     *         is the cheap signal that the arguments were passed the wrong way
     *         round (a longitude above 90 cannot be a latitude)
     */
    public static Point point(double lat, double lng) {
        if (lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException(
                    "Latitude out of range: " + lat + " (arguments are lat, lng — in that order)");
        }
        if (lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException(
                    "Longitude out of range: " + lng + " (arguments are lat, lng — in that order)");
        }
        // The one swap. x = longitude, y = latitude.
        return FACTORY.createPoint(new Coordinate(lng, lat));
    }

    /** Latitude of a point, i.e. its y ordinate. */
    public static double latOf(Point p) {
        return p.getY();
    }

    /** Longitude of a point, i.e. its x ordinate. */
    public static double lngOf(Point p) {
        return p.getX();
    }

    /**
     * The shared {@link GeometryFactory}, for Hibernate Spatial and for
     * geometry types this class does not yet wrap (ward MultiPolygons read
     * back from the database, for instance). Callers must not use it to build
     * points; use {@link #point(double, double)}.
     */
    public static GeometryFactory geometryFactory() {
        return FACTORY;
    }
}
