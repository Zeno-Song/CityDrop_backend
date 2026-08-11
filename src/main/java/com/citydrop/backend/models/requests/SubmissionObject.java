package com.citydrop.backend.models.requests;

public record SubmissionObject(
        String destination,
        double packageWeightLbs,
        int stationId,
        String vehicle
) {}
