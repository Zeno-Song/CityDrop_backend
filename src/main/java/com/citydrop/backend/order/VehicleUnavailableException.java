package com.citydrop.backend.order;

public class VehicleUnavailableException extends RuntimeException {
    public VehicleUnavailableException() {
        super("No enough such a vehicle");
    }
}
