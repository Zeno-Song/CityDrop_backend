package com.citydrop.backend.deliveryOption;

import com.citydrop.backend.db.entities.StationEntity;
import com.citydrop.backend.models.responses.DeliveryQuote;
import com.citydrop.backend.enums.VehicleType;
import com.citydrop.backend.db.StationRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
Assumptions:

 1, StationRepository shall provide 1 method: findAll(): List<StationEntity>, which obtain a list of all stationEntity
 2, StationEntity is a Spring Data JDBC entity

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
                    station.coordX(), station.coordY(), dest[0], dest[1]); // this is the distance between stations and destinations
            if (distanceMiles > station.radius()) {
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
                        station.stationId()));
            }
        }
        return quotes;
    }

    /**
     * This currently a mock method, given the name of the address, return its latitude and longtitude
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
