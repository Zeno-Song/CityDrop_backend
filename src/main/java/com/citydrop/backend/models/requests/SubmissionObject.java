package com.citydrop.backend.models.requests;

public record SubmissionObject(
        String destination,
        double packageWeightLbs,
        int stationId,
        String vehicle,
        boolean queueIfUnavailable   // Feature 2 - defaults to false; false/omitted keeps immediate-failure behavior
) {}