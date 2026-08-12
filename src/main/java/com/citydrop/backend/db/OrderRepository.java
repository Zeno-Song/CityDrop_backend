package com.citydrop.backend.db;

import com.citydrop.backend.db.entities.OrderEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OrderRepository extends CrudRepository<OrderEntity, Integer> {

    List<OrderEntity> findByUserId(int userId);

    List<OrderEntity> findByStationId(int stationId);

    List<OrderEntity> findByStatus(String status);
}

