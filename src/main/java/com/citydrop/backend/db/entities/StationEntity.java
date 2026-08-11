package com.citydrop.backend.db.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("stations")
public record StationEntity(
        @Id int stationId,
        double coordX,
        double coordY,
        double radius,
        int robotCount,
        int droneCount
) {}