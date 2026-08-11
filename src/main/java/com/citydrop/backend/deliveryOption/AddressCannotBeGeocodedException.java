package com.citydrop.backend.deliveryOption;

public class AddressCannotBeGeocodedException extends RuntimeException {
    public AddressCannotBeGeocodedException(String message) {
        super(message);
    }
}
