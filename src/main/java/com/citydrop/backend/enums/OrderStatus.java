package com.citydrop.backend.enums;

public enum OrderStatus {
    PENDING_DROPOFF,
    BEFORE_HALF_WAY,
    HALF_WAY,
    MORE_THAN_HALF_WAY,
    DELIVERED,
    CANCELLED,
    QUEUED;

    public boolean isTerminal() {
        return this == CANCELLED || this == DELIVERED;
    }
}