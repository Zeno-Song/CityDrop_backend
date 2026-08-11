package com.citydrop.backend.db.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("authorities")
public record AuthorityEntity(
        @Id int id,
        String username,
        String authority
) {}
