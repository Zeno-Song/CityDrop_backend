package com.citydrop.backend.deliveryOption;

import com.citydrop.backend.cache.GeocodeCache;
import com.citydrop.backend.cache.QuoteSnapshotCache;
import com.citydrop.backend.cache.TravelTimeCache;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.StationEntity;
import com.citydrop.backend.models.responses.DeliveryQuote;
import com.google.maps.GeoApiContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeliveryServiceQuoteSnapshotTest {

    private StationRepository stationRepository;
    private DeliveryAlgorithm deliveryAlgorithm;
    private QuoteSnapshotCache quoteSnapshotCache;
    private GeocodeCache geocodeCache;
    private TravelTimeCache travelTimeCache;
    private DeliveryService deliveryService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stationRepository = mock(StationRepository.class);
        deliveryAlgorithm = mock(DeliveryAlgorithm.class);
        quoteSnapshotCache = mock(QuoteSnapshotCache.class);
        geocodeCache = mock(GeocodeCache.class);
        travelTimeCache = mock(TravelTimeCache.class);
        deliveryService = spy(new DeliveryService(
                stationRepository,
                deliveryAlgorithm,
                mock(GeoApiContext.class),
                quoteSnapshotCache,
                geocodeCache,
                travelTimeCache
        ));
        doReturn(new double[]{37.78, -122.42})
                .when(deliveryService).geocode("1 Main St, San Francisco");
        // pass-through: exercise getDeliveryOptions without a real cache backing GeocodeCache/TravelTimeCache
        when(geocodeCache.getOrLoad(anyString(), any()))
                .thenAnswer(inv -> ((Supplier<double[]>) inv.getArgument(1)).get());
        when(travelTimeCache.getOrLoad(anyInt(), anyDouble(), anyDouble(), anyString(), any()))
                .thenAnswer(inv -> ((Supplier<Double>) inv.getArgument(4)).get());

        StationEntity station = new StationEntity(3, 37.77, -122.41, 10.0, 2, 2);
        when(stationRepository.findAll()).thenReturn(List.of(station));
        when(deliveryAlgorithm.computeDistanceMiles(37.77, -122.41, 37.78, -122.42))
                .thenReturn(1.0);
        when(deliveryAlgorithm.computeTime(eq(station), eq(37.78), eq(-122.42), anyString()))
                .thenReturn(18.0);
        when(deliveryAlgorithm.computeCost(eq(4.0), eq(station), anyString())).thenReturn(12.50);
    }

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedRequestStoresTheFinalQuotes() {
        List<DeliveryQuote> result = deliveryService.getDeliveryOptions(
                "1 Main St, San Francisco",
                4.0,
                42
        );

        ArgumentCaptor<List<DeliveryQuote>> quotesCaptor = ArgumentCaptor.forClass(List.class);
        verify(quoteSnapshotCache).put(eq(42), quotesCaptor.capture());
        assertEquals(result, quotesCaptor.getValue());
    }

    @Test
    void anonymousRequestDoesNotStoreAQuoteSnapshot() {
        deliveryService.getDeliveryOptions("1 Main St, San Francisco", 4.0);

        verifyNoInteractions(quoteSnapshotCache);
    }
}
