package com.citydrop.backend.deliveryOption;

public class AddressOutOfRangeException extends RuntimeException {
    public AddressOutOfRangeException() {
        super("Address cannot be delivered: Too far");
    }
}
