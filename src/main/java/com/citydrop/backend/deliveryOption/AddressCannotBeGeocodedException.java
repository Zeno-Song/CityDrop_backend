package com.citydrop.backend.deliveryOption;

public class AddressCannotBeGeocodedException extends RuntimeException {
    public  AddressCannotBeGeocodedException() {
        super("Address can't be geocoded");
    }
}
