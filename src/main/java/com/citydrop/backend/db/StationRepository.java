package com.citydrop.backend.db;

import com.citydrop.backend.db.entities.StationEntity;
import org.springframework.data.repository.ListCrudRepository;

public interface StationRepository extends ListCrudRepository<StationEntity, Integer> {
}
