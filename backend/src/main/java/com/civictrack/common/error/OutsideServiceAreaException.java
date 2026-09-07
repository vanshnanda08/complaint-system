package com.civictrack.common.error;

public class OutsideServiceAreaException extends RuntimeException {

    public OutsideServiceAreaException(double lat, double lng) {
        super("Coordinate (%.6f, %.6f) falls outside every serviced ward".formatted(lat, lng));
    }
}
