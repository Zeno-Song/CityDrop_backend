package com.citydrop.backend.deliveryOption;

import com.citydrop.backend.cache.GeocodeCache;
import com.citydrop.backend.cache.QuoteSnapshotCache;
import com.citydrop.backend.cache.TravelTimeCache;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.StationEntity;
import com.citydrop.backend.enums.VehicleType;
import com.citydrop.backend.models.responses.DeliveryQuote;
import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.errors.ApiException;
import com.google.maps.model.GeocodingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds delivery quotes: geocodes the destination, pulls all stations,
 * and produces one DeliveryQuote per (station x vehicle type).
 *
 * Assumptions:
 * 1. StationRepository shall provide findAll(): List<StationEntity>.
 * 2. StationEntity is a Spring Data JDBC entity.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final StationRepository stationRepository;
    private final DeliveryAlgorithm deliveryAlgorithm;
    private final GeoApiContext geoApiContext;
    private final QuoteSnapshotCache quoteSnapshotCache;
    private final GeocodeCache geocodeCache;
    private final TravelTimeCache travelTimeCache;

    public DeliveryService(StationRepository stationRepository,
                           DeliveryAlgorithm deliveryAlgorithm,
                           GeoApiContext geoApiContext,
                           QuoteSnapshotCache quoteSnapshotCache,
                           GeocodeCache geocodeCache,
                           TravelTimeCache travelTimeCache) {
        this.stationRepository = stationRepository;
        this.deliveryAlgorithm = deliveryAlgorithm;
        this.geoApiContext = geoApiContext;
        this.quoteSnapshotCache = quoteSnapshotCache;
        this.geocodeCache = geocodeCache;
        this.travelTimeCache = travelTimeCache;
    }

    public List<DeliveryQuote> getDeliveryOptions(String destinationAddress, double packageWeightLbs) {
        return getDeliveryOptions(destinationAddress, packageWeightLbs, null);
    }

    public List<DeliveryQuote> getDeliveryOptions(
            String destinationAddress,
            double packageWeightLbs,
            Integer userId
    ) {
        double[] dest = geocodeCache.getOrLoad(destinationAddress, () -> geocode(destinationAddress));

        List<StationEntity> stations = new ArrayList<>();
        stationRepository.findAll().forEach(stations::add);
        // findAll() makes no ordering guarantee -- without this the UI's station
        // cards would shuffle between requests instead of staying in a stable order.
        stations.sort(Comparator.comparingInt(StationEntity::stationId));

        List<DeliveryQuote> quotes = new ArrayList<>();
        for (StationEntity station : stations) {
            double distanceToStationMiles = deliveryAlgorithm.computeDistanceMiles(
                    station.coordX(), station.coordY(), dest[0], dest[1]);
            if (distanceToStationMiles > station.radius()) {
                continue;
            }
            for (VehicleType vehicle : VehicleType.values()) {
                double time = travelTimeCache.getOrLoad(
                        station.stationId(), dest[0], dest[1], vehicle.name(),
                        () -> deliveryAlgorithm.computeTime(station, dest[0], dest[1], vehicle.name())
                );
                double price = deliveryAlgorithm.computeCost(packageWeightLbs, vehicle.name());
                boolean available = (vehicle == VehicleType.ROBOT ? station.robotCount() : station.droneCount()) > 0;
                quotes.add(new DeliveryQuote(
                        destinationAddress, packageWeightLbs, vehicle.name(), price, time, station.stationId(),
                        available));
            }
        }

        if (quotes.isEmpty()) {
            throw new AddressOutOfRangeException();
        }
        if (userId != null) {
            quoteSnapshotCache.put(userId, quotes);
        }
        return quotes;
    }

    /**
     * Resolve a human-readable address to its latitude/longitude using the
     * Google Maps Geocoding API.
     *
     * @param address the destination address string, e.g. "1 Ferry Building, San Francisco, CA, 94105"
     * @return double[]{latitude, longitude}
     * @throws AddressCannotBeGeocodedException when the address is blank, or Google cannot
     *                                          geocode it (no results, partial match, or API error).
     */
    public double[] geocode(String address) {
        if (address == null || address.isBlank()) {
            throw new AddressCannotBeGeocodedException();
        }

        try {
            GeocodingResult[] results = GeocodingApi.geocode(geoApiContext, address).await();
            if (results.length == 0) {
                log.warn("Google Geocoding returned no results for address '{}'", address);
                throw new AddressCannotBeGeocodedException();
            }
            GeocodingResult result = results[0];
            if (result.partialMatch) {
                log.warn("Google Geocoding returned only a partial match for address '{}'", address);
                throw new AddressCannotBeGeocodedException();
            }
            double lat = result.geometry.location.lat;
            double lng = result.geometry.location.lng;
            return new double[]{lat, lng};
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Google Geocoding interrupted for address '{}'", address);
            throw new AddressCannotBeGeocodedException();
        } catch (IOException | ApiException e) {
            log.warn("Google Geocoding request failed for address '{}': {}", address, e.getMessage());
            throw new AddressCannotBeGeocodedException();
        }
    }
}
