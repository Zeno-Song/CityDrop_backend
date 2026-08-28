package com.citydrop.backend.cache;

// Thrown when the quote-lock cache itself is unreachable or broken (Redis down,
// corrupted entry, etc.) -- distinct from QuoteExpiredException, which means the
// cache was reachable and simply has no matching, unexpired quote for this user.
public class QuoteCacheUnavailableException extends RuntimeException {
    public QuoteCacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
