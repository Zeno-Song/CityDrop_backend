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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("concurrency")
@SpringBootTest
class DequeueConcurrencyTest {

    @Autowired private StationRepository stationRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderQueueService orderQueueService;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final int QUEUED_ORDERS = 10;
    private static final int CONCURRENT_RELEASES = 4; // fewer releases than queued orders
    private static volatile boolean sequencePrimed = false;

    @RepeatedTest(value = 50, name = "attempt {currentRepetition}/{totalRepetitions}")
    void concurrentReleasesNeverDoubleAssignAndRespectTheDropoffTimeOrderIdTiebreak() throws Exception {
        primeStationIdSequenceOnce();
        int stationId = stationRepository.save(
                new StationEntity(0, 37.7749, -122.4994, 5.0, 0, 0)
        ).stationId();
        int userId = registerUser();

        // Same dropped_off_at for every order -- forces the tiebreak in
        // OrderRepository.findOldestQueuedForUpdate to fall to order_id ascending.
        OffsetDateTime sameDropoffMoment = OffsetDateTime.now();
        List<OrderEntity> queued = new ArrayList<>();
        for (int i = 0; i < QUEUED_ORDERS; i++) {
            queued.add(orderRepository.save(new OrderEntity(
                    0, userId, "1 Main St, San Francisco", 4.0, 12.5, 18.0,
                    VehicleType.ROBOT.name(), stationId, OrderStatus.QUEUED.name(),
                    OffsetDateTime.now(), sameDropoffMoment, true)));
        }
        // IDENTITY column -> insertion order == ascending order_id
        List<Integer> expectedWinners = queued.stream()
                .map(OrderEntity::orderId)
                .sorted()
                .limit(CONCURRENT_RELEASES)
                .toList();

        CountDownLatch ready = new CountDownLatch(CONCURRENT_RELEASES);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_RELEASES; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                orderQueueService.handleVehicleAvailable(stationId, VehicleType.ROBOT.name());
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        List<OrderEntity> refreshed = orderRepository.findAllById(
                queued.stream().map(OrderEntity::orderId).toList());
        Set<Integer> promoted = refreshed.stream()
                .filter(o -> o.status().equals(OrderStatus.BEFORE_HALF_WAY.name()))
                .map(OrderEntity::orderId)
                .collect(Collectors.toSet());

        assertEquals(CONCURRENT_RELEASES, promoted.size(), "no order should be promoted twice, none skipped");
        assertEquals(Set.copyOf(expectedWinners), promoted,
                "the lowest order_id orders should win the dropped_off_at tiebreak");
        assertEquals(0, stationRepository.findByStationId(stationId).robotCount(),
                "a transfer to a queued order shouldn't touch the count -- only 'nobody queued' does");

        for (OrderEntity order : queued) orderRepository.deleteById(order.orderId());
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
