package com.citydrop.backend.db;

import com.citydrop.backend.db.entities.OrderEntity;
import org.springframework.core.annotation.Order;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository
        extends ListCrudRepository<OrderEntity, Integer> {

    List<OrderEntity> findByUserId(int userId);

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

    @Modifying
    @Query("""
        UPDATE orders
        SET status = :newStatus
        WHERE order_id = :orderId
          AND status = :oldStatus
        """)
    int updateStatus(
            @Param("orderId") int orderId,
            @Param("oldStatus") String oldStatus,
            @Param("newStatus") String newStatus
    );

    @Modifying
    @Query("""
    UPDATE orders
    SET status = 'AT_STATION',
        dropped_off_at = :now
    WHERE order_id = :orderId
      AND status = 'PENDING_DROPOFF'
    """)
    int markDroppedOff(
            @Param("orderId") int orderId,
            @Param("now") OffsetDateTime now
    );

    // Feature 2 - no-bypass: block a new order from grabbing a vehicle while any QUEUED order
    // exists for the same station + vehicle.
    @Query("""
        SELECT EXISTS (
            SELECT 1 FROM orders
            WHERE station_id = :stationId
              AND vehicle = :vehicle
              AND status = 'QUEUED'
        )
        """)
    boolean existsQueuedOrder(
            @Param("stationId") int stationId,
            @Param("vehicle") String vehicle
    );

    // Feature 2 - FIFO queue head, locked. Ordered by order_id (auto-increment), NOT created_at.
    @Query("""
        SELECT * FROM orders
        WHERE status = 'QUEUED'
          AND station_id = :stationId
          AND vehicle = :vehicle
        ORDER BY order_id ASC
        LIMIT 1
        FOR UPDATE
        """)
    OrderEntity findOldestQueuedForUpdate(
            @Param("stationId") int stationId,
            @Param("vehicle") String vehicle
    );

    // Feature 2 - CAS: QUEUED -> PENDING_DROPOFF. rows == 0 means the head was cancelled
    // concurrently; caller should try the next one.
    @Modifying
    @Query("""
        UPDATE orders
        SET status = 'PENDING_DROPOFF'
        WHERE order_id = :orderId
          AND status = 'QUEUED'
        """)
    int assignQueuedOrder(@Param("orderId") int orderId);
}