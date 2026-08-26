package com.citydrop.backend.cache;

import com.citydrop.backend.models.responses.DeliveryQuote;

import java.time.Instant;
import java.util.List;

public record QuoteSnapshot(
        int userId,
        Instant createdAt,
        Instant expiresAt,
        List<DeliveryQuote> quotes
) {
}
