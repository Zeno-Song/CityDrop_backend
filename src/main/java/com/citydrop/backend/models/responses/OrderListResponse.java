package com.citydrop.backend.models.responses;

import java.util.List;

public record OrderListResponse(
        List<OrderIdEntry> active,
        List<OrderIdEntry> completed
) {}
