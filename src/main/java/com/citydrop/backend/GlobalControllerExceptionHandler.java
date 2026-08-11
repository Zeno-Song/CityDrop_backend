package com.citydrop.backend;

import com.citydrop.backend.models.responses.ErrorResponse;
import com.citydrop.backend.deliveryOption.AddressCannotBeGeocodedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalControllerExceptionHandler {


    @ExceptionHandler(AddressCannotBeGeocodedException.class)
    public final ResponseEntity<ErrorResponse> handleException(AddressCannotBeGeocodedException e) {
        return new ResponseEntity<>(new ErrorResponse(
                "address cannot be geocoded",
                "address_invalid"),
                HttpStatus.NOT_FOUND);
    }
}