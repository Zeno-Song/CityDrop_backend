package com.citydrop.backend.db.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("orders")
public record OrderEntity(
        @Id int orderId,
        int userId,
        String destination,
        double packageWeightLbs,
        double price,
        String vehicle,
        int stationId,
        String status,
        String createdAt
) {}