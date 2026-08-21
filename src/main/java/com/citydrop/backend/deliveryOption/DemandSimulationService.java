package com.citydrop.backend.deliveryOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DemandSimulationService {
    private static final Logger log = LoggerFactory.getLogger(DemandSimulationService.class);

    private final SimulationProperties properties;
    private final Clock clock;
    private final AtomicReference<Double> demandFactor = new AtomicReference<>(0.0);

    @Autowired
    public DemandSimulationService(SimulationProperties properties, @Autowired(required = false) Clock clock) {
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        refreshDemandFactor();
    }

    public double currentDemandFactor() {
        return demandFactor.get();
    }

    @Scheduled(fixedRateString = "${citydrop.simulation.demand-update-ms}")
    void refreshDemandFactor() {
        try {
            ZoneId zoneId = ZoneId.of(properties.getZoneId());
            ZonedDateTime now = ZonedDateTime.now(clock.withZone(zoneId));

            double hourDecimal = now.getHour() + (now.getMinute() / 60.0) + (now.getSecond() / 3600.0);

            double numerator = hourDecimal - properties.getPeakHour();
            double denominator = properties.getPeakSpreadHours();
            double exponent = -0.5 * Math.pow(numerator / denominator, 2);
            double rawFactor = Math.exp(exponent);

            double clampedFactor = Math.max(0.0, Math.min(1.0, rawFactor));

            demandFactor.set(clampedFactor);
        } catch (Exception e) {
            log.error("Failed to refresh demand factor", e);
        }
    }
}



