package com.citydrop.backend.db.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

@Table("orders")
public record OrderEntity(
        @Id int orderId,
        int userId,
        String destination,
        double packageWeightLbs,
        double price,
        double time,
        String vehicle,
        int stationId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime droppedOffAt,
        boolean refundEligible,
        boolean queueIfUnavailable
) {}