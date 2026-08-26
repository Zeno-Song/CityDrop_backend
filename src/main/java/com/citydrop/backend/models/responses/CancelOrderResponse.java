package com.citydrop.backend.models.responses;

public record CancelOrderResponse(
        OrderObject order,
        boolean refundEligible
) {}
