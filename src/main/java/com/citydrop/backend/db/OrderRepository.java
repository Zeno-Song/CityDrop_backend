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
    SET status = 'BEFORE_HALF_WAY',
        dropped_off_at = :now,
        refund_eligible = false
    WHERE order_id = :orderId
      AND status = 'PENDING_DROPOFF'
    """)
    int markDroppedOff(
            @Param("orderId") int orderId,
            @Param("now") OffsetDateTime now
    );

    // Feature 2 - a vehicle isn't claimed at order time anymore (see
    // OrderQueueService.claimVehicleAtDropoff); if none is idle when the
    // package physically arrives, it joins the queue right then instead of
    // moving to BEFORE_HALF_WAY. dropped_off_at is still recorded here so
    // it's not re-counted as eligible for the scheduler until it's actually
    // assigned a vehicle (see assignQueuedOrderAtStation).
    @Modifying
    @Query("""
    UPDATE orders
    SET status = 'QUEUED',
        dropped_off_at = :now
    WHERE order_id = :orderId
      AND status = 'PENDING_DROPOFF'
    """)
    int markQueuedAtDropoff(
            @Param("orderId") int orderId,
            @Param("now") OffsetDateTime now
    );

    @Query("""
    SELECT * FROM orders
    WHERE dropped_off_at IS NOT NULL
      AND status IN ('BEFORE_HALF_WAY', 'HALF_WAY', 'MORE_THAN_HALF_WAY')
    """)
    List<OrderEntity> findEligibleForStatusAdvancement();

    // Feature 2 - queue head, locked. Ordered by dropped_off_at (the moment the package physically
    // arrived and joined the queue), tiebroken by order_id.
    @Query("""
        SELECT * FROM orders
        WHERE status = 'QUEUED'
          AND station_id = :stationId
          AND vehicle = :vehicle
        ORDER BY dropped_off_at ASC, order_id ASC
        LIMIT 1
        FOR UPDATE
        """)
    OrderEntity findOldestQueuedForUpdate(
            @Param("stationId") int stationId,
            @Param("vehicle") String vehicle
    );

    // Feature 2 - CAS: QUEUED -> BEFORE_HALF_WAY, for a queued order whose package was already
    // dropped off (droppedOffAt already set before this call -- see claimVehicleAtDropoff/
    // markQueuedAtDropoff; a QUEUED order can no longer exist any other way). Refreshes
    // dropped_off_at to now so the delivery clock starts from actually getting a vehicle, not from
    // whenever it happened to arrive while waiting. rows == 0 means the head was cancelled
    // concurrently; caller should try the next one.
    @Modifying
    @Query("""
        UPDATE orders
        SET status = 'BEFORE_HALF_WAY',
            dropped_off_at = :now,
            refund_eligible = false
        WHERE order_id = :orderId
          AND status = 'QUEUED'
        """)
    int assignQueuedOrderAtStation(
            @Param("orderId") int orderId,
            @Param("now") OffsetDateTime now
    );
}