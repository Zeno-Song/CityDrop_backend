package com.citydrop.backend;

import com.citydrop.backend.cache.QuoteCacheUnavailableException;
import com.citydrop.backend.chat.ChatUnavailableException;
import com.citydrop.backend.deliveryOption.AddressCannotBeGeocodedException;
import com.citydrop.backend.deliveryOption.AddressOutOfRangeException;
import com.citydrop.backend.deliveryOption.TimeEstimationFailureException;
import com.citydrop.backend.models.responses.ErrorResponse;
import com.citydrop.backend.order.InvalidOrderStatusException;
import com.citydrop.backend.order.OrderNotFoundException;
import com.citydrop.backend.order.QuoteExpiredException;
import com.citydrop.backend.user.UsernameTakenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AddressCannotBeGeocodedException.class)
    public ResponseEntity<ErrorResponse> handleAddressCannotBeGeocodedException(AddressCannotBeGeocodedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(AddressOutOfRangeException.class)
    public ResponseEntity<ErrorResponse> handleAddressInvalidException(AddressOutOfRangeException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(TimeEstimationFailureException.class)
    public ResponseEntity<ErrorResponse> handleTimeEstimationFailureException(TimeEstimationFailureException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(QuoteExpiredException.class)
    public ResponseEntity<ErrorResponse> handleQuoteExpiredException(QuoteExpiredException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(QuoteCacheUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleQuoteCacheUnavailableException(QuoteCacheUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("Quote service is temporarily unavailable."));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFoundException(OrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<ErrorResponse> handleUsernameTakenException(UsernameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(InvalidOrderStatusException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrderStatusException(InvalidOrderStatusException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(ChatUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleChatUnavailableException(ChatUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(ex.getMessage()));
    }
}
