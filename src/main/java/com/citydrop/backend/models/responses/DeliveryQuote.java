package com.citydrop.backend.models.responses;

public record DeliveryQuote(
        String destination,
        double packageWeightLbs,
        String vehicle,
        double price,
        double time,
        int stationId,
        // Best-effort snapshot of the station's vehicle count at quote time --
        // not reserved, so it can still change by the time the user submits
        // (submitOrder re-checks stock for real). Lets the UI show "Sold out"
        // up front instead of only discovering it on submission.
        boolean available
) {}
