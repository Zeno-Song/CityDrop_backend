package com.citydrop.backend;

import com.citydrop.backend.order.QuoteExpiredException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void quoteExpiredIsReturnedAsGone() {
        QuoteExpiredException exception = new QuoteExpiredException();

        var response = new GlobalExceptionHandler().handleQuoteExpiredException(exception);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals(exception.getMessage(), response.getBody().error());
    }
}
