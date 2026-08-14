package com.citydrop.backend.deliveryOption;

/**
 * Thrown when the delivery time for a ROBOT route cannot be estimated because the
 * Google Directions API call failed (network error) or returned no usable route.
 */
public class TimeEstimationFailureException extends RuntimeException {
    public TimeEstimationFailureException() {
        super("Time estimation failed: possible network error or unable to compute time");
    }
}
