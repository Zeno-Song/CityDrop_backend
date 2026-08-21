package com.citydrop.backend.deliveryOption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DemandSimulationServiceTest {

    private SimulationProperties properties;

    @BeforeEach
    public void setUp() {
        properties = new SimulationProperties();
        properties.setZoneId("America/Los_Angeles");
        properties.setPeakHour(13.0);
        properties.setPeakSpreadHours(3.0);
        properties.setInventoryWeight(0.70);
        properties.setDailyWeight(0.30);
        properties.setRobotCapacity(25);
        properties.setDroneCapacity(8);
    }

    @Test
    public void testDemandFactorAtPeakHour() {
        Instant peakInstant = Instant.parse("2026-08-18T20:00:00Z");
        Clock fixedClock = Clock.fixed(peakInstant, ZoneId.of("UTC"));

        DemandSimulationService service = new DemandSimulationService(properties, fixedClock);
        double factor = service.currentDemandFactor();

        assertEquals(1.0, factor, 1e-2);
        assertTrue(factor >= 0.0 && factor <= 1.0);
    }

    @Test
    public void testDemandFactorAtNight() {
        Instant nightInstant = Instant.parse("2026-08-18T08:00:00Z");
        Clock fixedClock = Clock.fixed(nightInstant, ZoneId.of("UTC"));

        DemandSimulationService service = new DemandSimulationService(properties, fixedClock);
        double factor = service.currentDemandFactor();

        assertTrue(factor < 0.1);
        assertTrue(factor >= 0.0 && factor <= 1.0);
    }

    @Test
    public void testPropertiesStartupFailure() {
        properties.setInventoryWeight(0.80);
        properties.setDailyWeight(0.30);

        assertThrows(IllegalStateException.class, () -> {
            properties.validateOnStartup();
        });
    }
}
