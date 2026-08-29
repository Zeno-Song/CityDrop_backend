package com.citydrop.backend.concurrency;

import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.OrderEntity;
import com.citydrop.backend.db.entities.StationEntity;
import com.citydrop.backend.enums.OrderStatus;
import com.citydrop.backend.enums.VehicleType;
import com.citydrop.backend.order.OrderQueueService;
import com.citydrop.backend.user.UserService;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real Postgres, no test-DB reset in this repo -- every repetition creates and
// tears down its own station/user/orders so runs never interfere with each other.
@Tag("concurrency")
@SpringBootTest
class StationInventoryConcurrencyTest {

    @Autowired private StationRepository stationRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderQueueService orderQueueService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final int CONCURRENT_CLAIMS = 10;
    private static final int ROBOT_COUNT = 1; // exactly one vehicle -> only one claimant can win
    private static volatile boolean sequencePrimed = false;

    @RepeatedTest(value = 50, name = "attempt {currentRepetition}/{totalRepetitions}")
    void onlyOneOfManyConcurrentClaimsWinsTheSingleAvailableRobot() throws Exception {
        primeStationIdSequenceOnce();
        int stationId = stationRepository.save(
                new StationEntity(0, 37.7749, -122.4994, 5.0, ROBOT_COUNT, 0)
        ).stationId();
        int userId = registerUser();

        List<OrderEntity> orders = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CLAIMS; i++) {
            orders.add(orderRepository.save(pendingDropoffOrder(userId, stationId)));
        }

        CountDownLatch ready = new CountDownLatch(CONCURRENT_CLAIMS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        List<Future<String>> futures = new ArrayList<>();
        for (OrderEntity order : orders) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return orderQueueService.claimVehicleAtDropoff(order);
            }));
        }
        ready.await();
        go.countDown();

        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger queued = new AtomicInteger();
        for (Future<String> f : futures) {
            String status = f.get(10, TimeUnit.SECONDS);
            if (status.equals(OrderStatus.BEFORE_HALF_WAY.name())) claimed.incrementAndGet();
            else if (status.equals(OrderStatus.QUEUED.name())) queued.incrementAndGet();
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, claimed.get(), "exactly one order should have claimed the single available robot");
        assertEquals(CONCURRENT_CLAIMS - 1, queued.get());
        assertTrue(stationRepository.findByStationId(stationId).robotCount() >= 0,
                "the DB-level CHECK is a backstop -- the app logic should never even ask it to fire");

        for (OrderEntity order : orders) orderRepository.deleteById(order.orderId());
        stationRepository.deleteById(stationId);
    }

    // stations.station_id is GENERATED ALWAYS AS IDENTITY, but data.sql seeds rows 1-3 via
    // OVERRIDING SYSTEM VALUE, which does NOT advance the sequence in Postgres. On a freshly
    // booted schema the sequence still starts at 1, so the first auto-generated insert in the
    // whole run would otherwise collide with a seeded station. Bump it once, idempotently.
    private void primeStationIdSequenceOnce() {
        if (!sequencePrimed) {
            jdbcTemplate.execute(
                    "SELECT setval(pg_get_serial_sequence('stations', 'station_id'), "
                            + "(SELECT GREATEST(MAX(station_id), 1) FROM stations))");
            sequencePrimed = true;
        }
    }

    private OrderEntity pendingDropoffOrder(int userId, int stationId) {
        return new OrderEntity(0, userId, "1 Main St, San Francisco", 4.0, 12.5, 18.0,
                VehicleType.ROBOT.name(), stationId, OrderStatus.PENDING_DROPOFF.name(),
                OffsetDateTime.now(), null, true);
    }

    private int registerUser() {
        String username = "race-" + UUID.randomUUID().toString().substring(0, 8);
        userService.register(username, "test-password");
        return userService.findByUsername(username).id();
    }
}
