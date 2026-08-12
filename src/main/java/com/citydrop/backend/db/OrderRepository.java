package com.citydrop.backend.db;

import com.citydrop.backend.db.entities.OrderEntity;
import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository
        extends ListCrudRepository<OrderEntity, Integer> {

    Optional<OrderEntity> findByUserIdAndOrderId(
            int userId,
            int orderId
    );

    List<OrderEntity> findByUserIdAndStatusNot(
            int userId,
            String status
    );

    List<OrderEntity> findByUserIdAndStatus(
            int userId,
            String status
    );
}