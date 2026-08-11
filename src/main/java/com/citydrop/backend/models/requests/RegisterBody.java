package com.citydrop.backend.models.requests;

public record RegisterBody(
        String username,
        String password
) {}
