package com.citydrop.backend.deliveryOption;

import com.citydrop.backend.db.entities.StationEntity;
import com.citydrop.backend.enums.VehicleType;
import org.springframework.stereotype.Component;

/**
 * Calculates delivery time and cost for one (station, destination, vehicle) combination.
 * Assumptions:
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
                station.coordX(), station.coordY(), destCoordX, destCoordY);
        double speedMph = switch (VehicleType.valueOf(vehicle)) {
            case ROBOT -> ROBOT_SPEED_MPH;
            case DRONE -> DRONE_SPEED_MPH;
        };
        return distanceMiles / speedMph * 60.0;
    }

    /**
     * Compute the cost based on package weight and the vehicle type, in USD.
     */
    public double computeCost(double packageWeightLbs, String vehicle) {
        double cost = switch (VehicleType.valueOf(vehicle)) {
            case ROBOT -> ROBOT_BASE_PRICE + ROBOT_PRICE_PER_LB * packageWeightLbs;
            case DRONE -> DRONE_BASE_PRICE + DRONE_PRICE_PER_LB * packageWeightLbs;
        };
        return Math.round(cost * 100.0) / 100.0;
    }

    /**
     * This is an algorithm used to compute the distance between two points on a sphere (the path is a curve)
     */
    public double computeDistanceMiles(double coordX1, double coordY1, double coordX2, double coordY2) {
        double lat1 = Math.toRadians(coordX1);
        double lat2 = Math.toRadians(coordX2);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(coordY2) - Math.toRadians(coordY1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_MILES * Math.asin(Math.sqrt(a));
    }
}
