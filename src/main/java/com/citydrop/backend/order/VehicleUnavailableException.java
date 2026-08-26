package com.citydrop.backend.order;

public class VehicleUnavailableException extends RuntimeException {
    public VehicleUnavailableException() {
        super("Not enough such vehicles");
    }
}
