package com.citydrop.backend.deliveryOption;

import com.citydrop.backend.db.entities.StationEntity;
import com.citydrop.backend.enums.VehicleType;
import com.google.maps.DirectionsApi;
import com.google.maps.GeoApiContext;
import com.google.maps.errors.ApiException;
import com.google.maps.model.DirectionsResult;
import com.google.maps.model.DirectionsRoute;
import com.google.maps.model.TravelMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Calculates delivery time and cost for one (station, destination, vehicle) combination.
 *
 * Distance model (agreed):
 * - ROBOT travels on the real San Francisco road network: time comes from the Google
 *   Directions API driving duration (in minutes). No distance is computed for ROBOT;
 *   if the API call fails or returns no route, a TimeEstimationFailureException is thrown.
 * - DRONE flies a straight line: time = haversine distance / DRONE_SPEED_MPH.
 *
 * coordX = latitude, coordY = longitude, in degrees (matches StationEntity).
 */
@Component
public class DeliveryAlgorithm {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAlgorithm.class);

    // ---- tunable constants ----
    private static final double DRONE_SPEED_MPH = 30.0; // fast but expensive

    private static final double ROBOT_BASE_PRICE = 2.0;
    private static final double ROBOT_PRICE_PER_LB = 0.5;
    private static final double DRONE_BASE_PRICE = 5.0;
    private static final double DRONE_PRICE_PER_LB = 1.0;

    // Demand-based surge: price scales up as a station's idle fleet (StationEntity.robotCount/
    // droneCount, live-updated by OrderQueueService as vehicles are claimed/released) runs low
    // relative to its seeded size (see data.sql). Full fleet idle -> no markup; fleet at 0 -> max markup.
    private static final int ROBOT_CAPACITY = 25;
    private static final int DRONE_CAPACITY = 8;
    private static final double MAX_MARKUP_RATE = 0.5;

    private static final double EARTH_RADIUS_MILES = 3958.8;

    private final GeoApiContext geoApiContext;

    public DeliveryAlgorithm(GeoApiContext geoApiContext) {
        this.geoApiContext = geoApiContext;
    }

    /**
     * Estimated delivery time from the station to the destination, in minutes.
     */
    public double computeTime(StationEntity station, double destCoordX, double destCoordY, String vehicle) {
        return switch (VehicleType.valueOf(vehicle)) {
            case ROBOT -> computeRobotTimeMinutes(station, destCoordX, destCoordY);
            case DRONE -> computeDroneTimeMinutes(station, destCoordX, destCoordY);
        };
    }

    /**
     * ROBOT: use the Google Directions API driving duration (real road network).
     * No distance is computed for ROBOT; throws TimeEstimationFailureException when the
     * API fails or returns no route.
     */
    private double computeRobotTimeMinutes(StationEntity station, double destCoordX, double destCoordY) {
        try {
            DirectionsResult result = DirectionsApi.newRequest(geoApiContext)
                    .origin(station.coordX() + "," + station.coordY())
                    .destination(destCoordX + "," + destCoordY)
                    .mode(TravelMode.DRIVING)
                    .await();
            DirectionsRoute[] routes = result.routes;
            if (routes != null && routes.length > 0
                    && routes[0].legs != null && routes[0].legs.length > 0
                    && routes[0].legs[0].duration != null) {
                return routes[0].legs[0].duration.inSeconds / 60.0;
            }
            log.warn("Directions API returned no route for station {} -> ({},{})",
                    station.stationId(), destCoordX, destCoordY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Directions API interrupted for station {} -> ({},{})",
                    station.stationId(), destCoordX, destCoordY);
        } catch (IOException | ApiException e) {
            log.warn("Directions API failed for station {} -> ({},{}): {}",
                    station.stationId(), destCoordX, destCoordY, e.getMessage());
        }
        throw new TimeEstimationFailureException();
    }

    /**
     * DRONE: straight-line flight, haversine distance at a fixed speed.
     */
    private double computeDroneTimeMinutes(StationEntity station, double destCoordX, double destCoordY) {
        double distanceMiles = computeDistanceMiles(
                station.coordX(), station.coordY(), destCoordX, destCoordY);
        return distanceMiles / DRONE_SPEED_MPH * 60.0;
    }

    /**
     * Compute the cost based on package weight, the station's current idle fleet, and the vehicle
     * type, in USD. Applies a demand-based surge of up to MAX_MARKUP_RATE as the station's idle
     * count for that vehicle drops toward zero.
     */
    public double computeCost(double packageWeightLbs, StationEntity station, String vehicle) {
        VehicleType vehicleType = VehicleType.valueOf(vehicle);
        double baseCost = switch (vehicleType) {
            case ROBOT -> ROBOT_BASE_PRICE + ROBOT_PRICE_PER_LB * packageWeightLbs;
            case DRONE -> DRONE_BASE_PRICE + DRONE_PRICE_PER_LB * packageWeightLbs;
        };

        int available = vehicleType == VehicleType.ROBOT ? station.robotCount() : station.droneCount();
        int capacity = vehicleType == VehicleType.ROBOT ? ROBOT_CAPACITY : DRONE_CAPACITY;
        double scarcity = Math.max(0.0, Math.min(1.0, 1.0 - ((double) available / capacity)));
        double markupRate = MAX_MARKUP_RATE * scarcity;

        double finalCost = baseCost * (1.0 + markupRate);
        return Math.round(finalCost * 100.0) / 100.0;
    }

    /**
     * This is an algorithm used to compute the distance between two points on a sphere (the path is a curve)
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
