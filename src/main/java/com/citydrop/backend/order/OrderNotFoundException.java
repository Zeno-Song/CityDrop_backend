package com.citydrop.backend.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException() {
        super("order not found");
    }
}