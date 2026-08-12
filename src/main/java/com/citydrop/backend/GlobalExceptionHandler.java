package com.citydrop.backend;

import com.citydrop.backend.deliveryOption.AddressCannotBeGeocodedException;
import com.citydrop.backend.models.responses.ErrorResponse;
import com.citydrop.backend.order.OrderNotFoundException;
import com.citydrop.backend.order.VehicleUnavailableException;
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


    @ExceptionHandler(VehicleUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleVehicleUnavailableException(VehicleUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFoundException(OrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<ErrorResponse> handleUsernameTakenException(UsernameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }
}
