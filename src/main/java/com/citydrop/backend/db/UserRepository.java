package com.citydrop.backend.db;

import com.citydrop.backend.db.entities.UserEntity;
import org.springframework.data.repository.ListCrudRepository;

public interface UserRepository extends ListCrudRepository<UserEntity, Integer> {

    UserEntity findByUsername(String username);
}