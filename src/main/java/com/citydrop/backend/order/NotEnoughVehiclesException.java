package com.citydrop.backend.order;

public class NotEnoughVehiclesException extends RuntimeException {

    public NotEnoughVehiclesException() {
        super("not enough vehicles");
    }
}