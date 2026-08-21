package com.citydrop.backend.deliveryOption;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Component
@ConfigurationProperties(prefix = "citydrop.simulation")
@Validated
public class SimulationProperties {
    private boolean enabled;
    @NotNull private String zoneId;
    @Min(1) private int robotCapacity;
    @Min(1) private int droneCapacity;
    private double inventoryWeight;
    private double dailyWeight;
    private double maxMarkupRate;
    private double peakHour;
    private double peakSpreadHours;

    @PostConstruct
    public void validateOnStartup() {
        if (Math.abs((inventoryWeight + dailyWeight) - 1.0) > 1e-6) {
            throw new IllegalStateException("SimulationProperties: inventoryWeight and dailyWeight must sum to 1.0");
        }
        if (robotCapacity <= 0 || droneCapacity <= 0) {
            throw new IllegalStateException("SimulationProperties: Capacities must be greater than 0");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }
    public int getRobotCapacity() { return robotCapacity; }
    public void setRobotCapacity(int robotCapacity) { this.robotCapacity = robotCapacity; }
    public int getDroneCapacity() { return droneCapacity; }
    public void setDroneCapacity(int droneCapacity) { this.droneCapacity = droneCapacity; }
    public double getInventoryWeight() { return inventoryWeight; }
    public void setInventoryWeight(double inventoryWeight) { this.inventoryWeight = inventoryWeight; }
    public double getDailyWeight() { return dailyWeight; }
    public void setDailyWeight(double dailyWeight) { this.dailyWeight = dailyWeight; }
    public double getMaxMarkupRate() { return maxMarkupRate; }
    public void setMaxMarkupRate(double maxMarkupRate) { this.maxMarkupRate = maxMarkupRate; }
    public double getPeakHour() { return peakHour; }
    public void setPeakHour(double peakHour) { this.peakHour = peakHour; }
    public double getPeakSpreadHours() { return peakSpreadHours; }
    public void setPeakSpreadHours(double peakSpreadHours) { this.peakSpreadHours = peakSpreadHours; }
}
