package com.citydrop.backend.models.responses;

public record ErrorResponse(
        String message,
        String error
) {
}
