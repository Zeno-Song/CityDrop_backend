package com.citydrop.backend.cache;

import com.citydrop.backend.models.responses.DeliveryQuote;

import java.time.Instant;
import java.util.List;

public record QuoteSnapshot(int userId, List<TimedQuote> quotes) {

    // Each quote carries its own lock expiry so merging a newer fetch into this
    // user's entry (see QuoteSnapshotCache.put) never resets -- or drops -- the
    // remaining validity of a different quote that's still within its own lock window.
    public record TimedQuote(DeliveryQuote quote, Instant expiresAt) {}
}
