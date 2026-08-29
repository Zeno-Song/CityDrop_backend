package com.citydrop.backend.concurrency;

import com.citydrop.backend.db.OrderRepository;
import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.db.entities.OrderEntity;
import com.citydrop.backend.db.entities.StationEntity;
import com.citydrop.backend.enums.OrderStatus;
import com.citydrop.backend.enums.VehicleType;
import com.citydrop.backend.order.InvalidOrderStatusException;
import com.citydrop.backend.order.OrderService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("concurrency")
@SpringBootTest
class OrderCancelConcurrencyTest {

    @Autowired private StationRepository stationRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderService orderService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final int CONCURRENT_CANCELS = 10;
    private static final int STARTING_ROBOT_COUNT = 5;
    private static volatile boolean sequencePrimed = false;

    @RepeatedTest(value = 50, name = "attempt {currentRepetition}/{totalRepetitions}")
    void onlyOneOfManyConcurrentCancelsWinsAndOnlyOneReleaseHappens() throws Exception {
        primeStationIdSequenceOnce();
        int stationId = stationRepository.save(
                new StationEntity(0, 37.7749, -122.4994, 5.0, STARTING_ROBOT_COUNT, 0)
        ).stationId();
        int userId = registerUser();

        // Simulate an order that already claimed a robot (BEFORE_HALF_WAY), the same way
        // claimVehicleAtDropoff would have left it: count decremented, order past PENDING_DROPOFF.
        stationRepository.decrementRobotCount(stationId);
        OrderEntity order = orderRepository.save(new OrderEntity(
                0, userId, "1 Main St, San Francisco", 4.0, 12.5, 18.0,
                VehicleType.ROBOT.name(), stationId, OrderStatus.BEFORE_HALF_WAY.name(),
                OffsetDateTime.now(), OffsetDateTime.now(), false));

        CountDownLatch ready = new CountDownLatch(CONCURRENT_CANCELS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CANCELS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    orderService.cancelOrder(userId, order.orderId());
                    return true;
                } catch (InvalidOrderStatusException expected) {
                    return false; // lost the CAS race -- order was already CANCELLED
                }
            }));
        }
        ready.await();
        go.countDown();

        long successes = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(10, TimeUnit.SECONDS)) successes++;
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, successes, "exactly one concurrent cancel should succeed");
        assertEquals(OrderStatus.CANCELLED.name(),
                orderRepository.findById(order.orderId()).orElseThrow().status());
        assertEquals(STARTING_ROBOT_COUNT, stationRepository.findByStationId(stationId).robotCount(),
                "the robot should be counted back exactly once -- not once per losing cancel attempt");

        orderRepository.deleteById(order.orderId());
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

    private int registerUser() {
        String username = "race-" + UUID.randomUUID().toString().substring(0, 8);
        userService.register(username, "test-password");
        return userService.findByUsername(username).id();
    }
}
