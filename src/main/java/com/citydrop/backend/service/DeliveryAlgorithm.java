package com.citydrop.backend.service;

import com.citydrop.backend.entity.StationEntity;
import com.citydrop.backend.model.VehicleType;
import org.springframework.stereotype.Component;

/**
 * Calculates delivery time and cost for one (station, destination, vehicle) combination.
 *
 * Assumptions (agreed for the base project):
 * - Straight-line distance is used for both vehicles; the robot's lower speed
 *   accounts for it being bound to roads.
 * - coordX = latitude, coordY = longitude, in degrees (matches StationEntity).
 * - Time is returned in MINUTES, cost in USD rounded to 2 decimals.
 */
@Component
public class DeliveryAlgorithm {

    // ---- tunable constants ----
    private static final double ROBOT_SPEED_MPH = 10.0; // slow but cheap
    private static final double DRONE_SPEED_MPH = 30.0; // fast but expensive

    private static final double ROBOT_BASE_PRICE = 2.0;
    private static final double ROBOT_PRICE_PER_LB = 0.5;
    private static final double DRONE_BASE_PRICE = 5.0;
    private static final double DRONE_PRICE_PER_LB = 1.0;

    private static final double EARTH_RADIUS_MILES = 3958.8;

    /**
     * Estimated delivery time from the station to the destination, in minutes.
     */
    public double computeTime(StationEntity station, double destCoordX, double destCoordY, String vehicle) {
        double distanceMiles = computeDistanceMiles(
                station.getCoordX(), station.getCoordY(), destCoordX, destCoordY);
        double speedMph = switch (VehicleType.valueOf(vehicle)) {
            case ROBOT -> ROBOT_SPEED_MPH;
            case DRONE -> DRONE_SPEED_MPH;
        };
        return distanceMiles / speedMph * 60.0;
    }

    /**
     * Delivery cost in USD: base price + per-pound rate, rounded to 2 decimals.
     */
    public double computeCost(double packageWeightLbs, String vehicle) {
        double cost = switch (VehicleType.valueOf(vehicle)) {
            case ROBOT -> ROBOT_BASE_PRICE + ROBOT_PRICE_PER_LB * packageWeightLbs;
            case DRONE -> DRONE_BASE_PRICE + DRONE_PRICE_PER_LB * packageWeightLbs;
        };
        return Math.round(cost * 100.0) / 100.0;
    }

    /**
     * Straight-line (haversine) distance between two coordinates, in miles.
     * coordX is latitude, coordY is longitude, both in degrees.
     */
    public double computeDistanceMiles(double coordX1, double coordY1, double coordX2, double coordY2) {
        double lat1 = Math.toRadians(coordX1);
        double lat2 = Math.toRadians(coordX2);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(coordY2 - coordY1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_MILES * Math.asin(Math.sqrt(a));
    }
}
