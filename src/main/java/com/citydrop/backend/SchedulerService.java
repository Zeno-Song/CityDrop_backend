package com.citydrop.backend;

import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.entities.OrderEntity;
import com.citydrop.backend.enums.OrderStatus;
import com.citydrop.backend.order.OrderQueueService; // Feature 2 — does not exist in the repo yet, see note below
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

// Feature 5 — job 1 (status advancement) only; job 2 (demand pricing) is out of scope for this pass.
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private static final long STATUS_TICK_INTERVAL_MS = 20_000; // 20s; tune once real order volume is known
    private static final double HALF_WAY_BAND = 0.10;

    private final OrderRepository orderRepository;
    private final OrderQueueService orderQueueService;

    public SchedulerService(OrderRepository orderRepository, OrderQueueService orderQueueService) {
        this.orderRepository = orderRepository;
        this.orderQueueService = orderQueueService;
    }

    @Scheduled(fixedRate = STATUS_TICK_INTERVAL_MS)
    public void advanceStatuses() {
        Instant tickStart = Instant.now();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<OrderEntity> eligible = orderRepository.findEligibleForStatusAdvancement();

        int advanced = 0;
        for (OrderEntity order : eligible) {
            try {
                if (advanceOne(order, now)) {
                    advanced++;
                }
            } catch (RuntimeException e) {
                log.warn("status-advancement tick: failed to advance order {}, skipping", order.orderId(), e);
            }
        }

        long elapsedMs = Duration.between(tickStart, Instant.now()).toMillis();
        log.info("status-advancement tick: {} eligible, {} advanced, {} ms", eligible.size(), advanced, elapsedMs);
    }

    private boolean advanceOne(OrderEntity order, OffsetDateTime now) {
        String currentStatus = order.status();
        OrderStatus target = computeProgressStatus(order, now);
        if (target.name().equals(currentStatus)) {
            return false;
        }

        int rowsAffected = orderRepository.updateStatus(order.orderId(), currentStatus, target.name());
        if (rowsAffected == 0) {
            return false; // moved by something else mid-tick (e.g. a cancellation) — not an error
        }

        if (target == OrderStatus.BEFORE_HALF_WAY) {
            orderRepository.clearRefundEligibility(order.orderId());
        }

        if (target == OrderStatus.DELIVERED) {
            orderQueueService.handleVehicleAvailable(order.stationId(), order.vehicle());
        }

        return true;
    }

    // Copied from OrderService.computeProgressStatus, not shared — see note below.
    private static OrderStatus computeProgressStatus(OrderEntity order, OffsetDateTime now) {
        OffsetDateTime droppedOffAt = order.droppedOffAt();
        double elapsedMinutes = Duration.between(droppedOffAt, now).toMillis() / 60000.0;
        double ratio = elapsedMinutes / order.time();

        if (ratio >= 1.0) return OrderStatus.DELIVERED;
        if (ratio >= 0.5 + HALF_WAY_BAND) return OrderStatus.MORE_THAN_HALF_WAY;
        if (ratio >= 0.5 - HALF_WAY_BAND) return OrderStatus.HALF_WAY;
        return OrderStatus.BEFORE_HALF_WAY;
    }
}