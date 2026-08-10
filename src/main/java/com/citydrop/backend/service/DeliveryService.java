package com.citydrop.backend.service;

import com.citydrop.backend.entity.StationEntity;
import com.citydrop.backend.exception.AddressCannotBeGeocodedException;
import com.citydrop.backend.model.DeliveryQuote;
import com.citydrop.backend.model.VehicleType;
import com.citydrop.backend.repository.StationRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds delivery quotes: geocodes the destination, pulls all stations,
 * and produces one DeliveryQuote per (in-range station x vehicle type).
 *
 * Behavior agreed with the team:
 * - Stations whose straight-line distance to the destination exceeds their
 *   radius are filtered out, so fewer than 6 quotes may be returned.
 * - If NO station can reach the destination, an empty list is returned (200).
 * - Vehicle availability (robotCount/droneCount) is NOT checked here; it is
 *   validated at order submission time (POST /order -> 409).
 */
@Service
public class DeliveryService {

    // Mock geocoding bounds: San Francisco, roughly a 7x7 mile area.
    private static final double SF_MIN_LAT = 37.70;
    private static final double SF_MAX_LAT = 37.81;
    private static final double SF_MIN_LNG = -122.52;
    private static final double SF_MAX_LNG = -122.35;

    private final StationRepository stationRepository;
    private final DeliveryAlgorithm deliveryAlgorithm;

    public DeliveryService(StationRepository stationRepository, DeliveryAlgorithm deliveryAlgorithm) {
        this.stationRepository = stationRepository;
        this.deliveryAlgorithm = deliveryAlgorithm;
    }

    public List<DeliveryQuote> getDeliveryOptions(String destinationAddress, double packageWeightLbs) {
        double[] dest = geocode(destinationAddress); // [latitude, longitude]

        List<DeliveryQuote> quotes = new ArrayList<>();
        for (StationEntity station : stationRepository.findAll()) { // Firstly, iterate 3 stations' positions
            double distanceMiles = deliveryAlgorithm.computeDistanceMiles(
                    station.getCoordX(), station.getCoordY(), dest[0], dest[1]); // this is the distance between stations and destinations
            if (distanceMiles > station.getRadius()) {
                continue; // station cannot cover this destination
            }
            for (VehicleType vehicle : VehicleType.values()) {
                double time = deliveryAlgorithm.computeTime(station, dest[0], dest[1], vehicle.name());
                double price = deliveryAlgorithm.computeCost(packageWeightLbs, vehicle.name());
                quotes.add(new DeliveryQuote(
                        destinationAddress,
                        packageWeightLbs,
                        vehicle.name(),
                        price,
                        time,
                        station.getStationId()));
            }
        }
        return quotes;
    }

    /**
     * TODO(geocoding): this is a deterministic MOCK standing in for the Google Maps
     * Geocoding API. It hashes the address into a coordinate inside the SF bounding
     * box so the same address always maps to the same point. To go live, replace the
     * body of this method with a call to the Geocoding API (and return lat/lng from
     * the first result); throw AddressCannotBeGeocodedException when the API returns
     * ZERO_RESULTS. Everything else in this class stays unchanged.
     *
     * @return double[]{latitude, longitude}
     */
    private double[] geocode(String address) {
        if (address == null || address.isBlank()) {
            throw new AddressCannotBeGeocodedException(address);
        }
        int hash = address.trim().toLowerCase().hashCode();
        double latFrac = (hash & 0xFFFF) / 65536.0;
        double lngFrac = ((hash >>> 16) & 0xFFFF) / 65536.0;
        double lat = SF_MIN_LAT + latFrac * (SF_MAX_LAT - SF_MIN_LAT);
        double lng = SF_MIN_LNG + lngFrac * (SF_MAX_LNG - SF_MIN_LNG);
        return new double[]{lat, lng};
    }
}
