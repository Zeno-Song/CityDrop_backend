package com.citydrop.backend.models.responses;

public record OrderObject(
        int orderId,
        String destination,
        double packageWeightLbs,
        double price,
        double time,
        String vehicle,
        int stationId,
        String status,
        String createdAt
) {}
