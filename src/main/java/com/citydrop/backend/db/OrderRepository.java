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

    @Query("""
    SELECT * FROM orders
    WHERE dropped_off_at IS NOT NULL
      AND status IN ('AT_STATION', 'BEFORE_HALF_WAY', 'HALF_WAY', 'MORE_THAN_HALF_WAY')
    """)
    List<OrderEntity> findEligibleForStatusAdvancement();

    @Modifying
    @Query("""
    UPDATE orders
    SET refund_eligible = false
    WHERE order_id = :orderId
    """)
    void clearRefundEligibility(@Param("orderId") int orderId);
}