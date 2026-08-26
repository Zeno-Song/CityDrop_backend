package com.citydrop.backend.order;

public class QuoteExpiredException extends RuntimeException {
    public QuoteExpiredException() {
        super("quote expired, request delivery options again");
    }
}
