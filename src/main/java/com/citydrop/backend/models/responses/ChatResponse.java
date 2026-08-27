package com.citydrop.backend.models.responses;

public record ChatResponse(
        String reply,
        boolean suggestCreateOrder,
        boolean offerHumanHelp,
        Integer suggestCancelOrderId,
        String suggestedDestination,
        Double suggestedWeightLbs
) {}
