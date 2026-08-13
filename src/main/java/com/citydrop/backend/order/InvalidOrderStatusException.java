package com.citydrop.backend.order;

public class InvalidOrderStatusException extends RuntimeException {
    public InvalidOrderStatusException(String currentStatus) {
        super("Order is currently " + currentStatus);
    }
}