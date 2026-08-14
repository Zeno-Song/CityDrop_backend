package com.citydrop.backend.db.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("users")
public record UserEntity(
        @Id int id,
        String username,
        String password,
        boolean enabled
) {}