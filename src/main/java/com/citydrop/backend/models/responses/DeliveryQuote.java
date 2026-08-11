package com.citydrop.backend.models.responses;

public record DeliveryQuote(
        String destination,
        double packageWeightLbs,
        String vehicle,
        double price,
        double time,
        int stationId
) {}
