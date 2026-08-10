package com.citydrop.backend.models.requests;

public record PackageRequestBody(
        String destStreet,
        String destCity,
        String destState,
        String destZip,
        double packageWeight
) {}
