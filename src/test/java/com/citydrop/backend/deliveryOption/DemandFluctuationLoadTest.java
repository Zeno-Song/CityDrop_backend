package com.citydrop.backend.deliveryOption;

import com.citydrop.backend.db.StationRepository;
import com.citydrop.backend.enums.OrderStatus;
import com.citydrop.backend.enums.VehicleType;
import com.citydrop.backend.models.requests.SubmissionObject;
import com.citydrop.backend.models.responses.OrderObject;
import com.citydrop.backend.order.OrderService;
import com.citydrop.backend.user.UserService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives DRONE demand at station 1 through a Gaussian-shaped concurrency curve --
 * ramping the number of currently-claimed, unreleased vehicles between 1 and 15 and
 * back down -- and asserts computeCost's surge pricing tracks it. 15 concurrent users
 * against an 8-drone station deliberately overshoots capacity, so the run should pass
 * through a stretch with zero idle drones and orders landing in QUEUED.
 */
@Tag("load")
@SpringBootTest
class DemandFluctuationLoadTest {

    private static final Logger log = LoggerFactory.getLogger(DemandFluctuationLoadTest.class);

    private static final int STATION_ID = 1;
    private static final String VEHICLE = VehicleType.DRONE.name();
    private static final String DESTINATION = "1000 Great Highway, San Francisco"; // ~0.7mi from station 1 (37.7749,-122.4994); must stay within its 5mi radius
    private static final double PACKAGE_WEIGHT_LBS = 4.0;

    private static final int MIN_CONCURRENT_USERS = 1;
    private static final int MAX_CONCURRENT_USERS = 15;
    private static final double MEAN_SECONDS = 10.0;
    private static final double STDDEV_SECONDS = 3.0;
    private static final long RUN_SECONDS = 20;
    private static final long TICK_MILLIS = 250;
    private static final double PRICE_TOLERANCE = 0.01;

    @Autowired private DeliveryService deliveryService;
    @Autowired private OrderService orderService;
    @Autowired private UserService userService;
    @Autowired private StationRepository stationRepository;

    private record HeldClaim(int userId, int orderId) {}
    private record Sample(double elapsedSeconds, int target, int actualHeld, int idleVehicles, double price) {}

    private static final class DemandState {
        final Deque<Integer> idleSlots;
        final Deque<HeldClaim> heldClaims = new ConcurrentLinkedDeque<>();
        final AtomicInteger outstanding = new AtomicInteger(0); // claiming + held + releasing
        final AtomicInteger heldCount = new AtomicInteger(0);   // confirmed holds only
        final AtomicInteger queuedCount = new AtomicInteger(0); // cumulative QUEUED outcomes
        final List<Sample> samples = new CopyOnWriteArrayList<>();

        DemandState(List<Integer> userIds) {
            this.idleSlots = new ConcurrentLinkedDeque<>(userIds);
        }
    }

    @Test
    void priceTracksAGaussianDemandCurve() throws Exception {
        int startingIdle = stationRepository.findByStationId(STATION_ID).droneCount();
        assertTrue(startingIdle > 0 && startingIdle < MAX_CONCURRENT_USERS,
                "station's idle drone count (" + startingIdle + ") must be under MAX_CONCURRENT_USERS ("
                        + MAX_CONCURRENT_USERS + ") for this run to actually saturate it");

        double baseline = currentPrice();
        log.info(String.format("baseline (no markup) price for %s @ %.1flbs: $%.2f",
                VEHICLE, PACKAGE_WEIGHT_LBS, baseline));

        DemandState state = new DemandState(registerSlotUsers(MAX_CONCURRENT_USERS));
        ExecutorService virtualUsers = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService clock = Executors.newScheduledThreadPool(2);
        AtomicBoolean draining = new AtomicBoolean(false);
        long startNanos = System.nanoTime();

        clock.scheduleAtFixedRate(() -> {
            try {
                double elapsed = secondsSince(startNanos);
                int target = draining.get() ? 0 : gaussianTarget(elapsed);
                int idle = stationRepository.findByStationId(STATION_ID).droneCount();
                double price = currentPrice();
                state.samples.add(new Sample(elapsed, target, state.heldCount.get(), idle, price));
                double markupPct = (price - baseline) / baseline * 100.0;
                log.info(String.format("t=%5.2fs target=%2d held=%2d idle=%2d price=$%.2f markup=%+.1f%%",
                        elapsed, target, state.heldCount.get(), idle, price, markupPct));
            } catch (Exception e) {
                log.warn("sample tick failed", e);
            }
        }, 0, TICK_MILLIS, TimeUnit.MILLISECONDS);

        ScheduledFuture<?> demand = clock.scheduleAtFixedRate(() -> {
            try {
                int target = draining.get() ? 0 : gaussianTarget(secondsSince(startNanos));
                int delta = target - state.outstanding.get();
                if (delta > 0) {
                    grow(delta, state, virtualUsers);
                } else if (delta < 0) {
                    shrink(-delta, state, virtualUsers);
                }
            } catch (Exception e) {
                log.warn("demand tick failed", e);
            }
        }, 0, TICK_MILLIS, TimeUnit.MILLISECONDS);

        Thread.sleep(Duration.ofSeconds(RUN_SECONDS).toMillis());
        draining.set(true);              // stop honoring the min-1 floor, let outstanding fall to 0
        awaitDrained(state.outstanding); // demand task is still running here, actively releasing down to 0
        demand.cancel(false);
        clock.shutdownNow();
        virtualUsers.shutdown();
        assertTrue(virtualUsers.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(startingIdle, stationRepository.findByStationId(STATION_ID).droneCount(),
                "every claimed drone should have been released back by the end of the run");

        double expectedPeak = Math.round(baseline * 1.5 * 100.0) / 100.0; // 1 + MAX_MARKUP_RATE from DeliveryAlgorithm
        double nearBaselineCeiling = baseline + (expectedPeak - baseline) * 0.25;

        double observedPeak = state.samples.stream().mapToDouble(Sample::price).max().orElseThrow();
        double startPrice = state.samples.get(0).price();
        double endPrice = state.samples.get(state.samples.size() - 1).price();

        assertEquals(expectedPeak, observedPeak, PRICE_TOLERANCE, "peak price should clamp at the max markup");
        assertTrue(startPrice <= nearBaselineCeiling,
                "price at the start (" + startPrice + ") should be near baseline (" + baseline + "), not near peak");
        assertTrue(endPrice <= nearBaselineCeiling,
                "price at the end (" + endPrice + ") should be near baseline (" + baseline + "), not near peak");

        assertTrue(state.samples.stream().anyMatch(s -> s.idleVehicles() == 0),
                "at least one sample should show zero idle drones at the station");
        assertTrue(state.queuedCount.get() > 0,
                "at least one order should have queued because no vehicle was idle at drop-off");
    }

    private static int gaussianTarget(double elapsedSeconds) {
        double t = elapsedSeconds - MEAN_SECONDS;
        double gaussian = Math.exp(-(t * t) / (2 * STDDEV_SECONDS * STDDEV_SECONDS));
        int scaled = (int) Math.round(MAX_CONCURRENT_USERS * gaussian);
        return Math.max(MIN_CONCURRENT_USERS, scaled);
    }

    private static double secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static void awaitDrained(AtomicInteger outstanding) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (outstanding.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(TICK_MILLIS);
        }
    }

    private double currentPrice() {
        return deliveryService.getDeliveryOptions(DESTINATION, PACKAGE_WEIGHT_LBS).stream()
                .filter(q -> q.stationId() == STATION_ID && q.vehicle().equals(VEHICLE))
                .findFirst()
                .orElseThrow()
                .price();
    }

    private void grow(int count, DemandState state, ExecutorService pool) {
        for (int i = 0; i < count; i++) {
            Integer userId = state.idleSlots.poll();
            if (userId == null) return; // every slot is already claiming or holding
            state.outstanding.incrementAndGet();
            pool.submit(() -> claim(userId, state));
        }
    }

    private void shrink(int count, DemandState state, ExecutorService pool) {
        for (int i = 0; i < count; i++) {
            HeldClaim held = state.heldClaims.poll();
            if (held == null) return;
            state.heldCount.decrementAndGet();
            pool.submit(() -> release(held, state));
        }
    }

    private void claim(int userId, DemandState state) {
        try {
            deliveryService.getDeliveryOptions(DESTINATION, PACKAGE_WEIGHT_LBS, userId);
            OrderObject order = orderService.submitOrder(userId,
                    new SubmissionObject(DESTINATION, PACKAGE_WEIGHT_LBS, STATION_ID, VEHICLE));
            String status = orderService.dropOff(userId, order.orderId());

            if (status.equals(OrderStatus.BEFORE_HALF_WAY.name())) {
                state.heldClaims.add(new HeldClaim(userId, order.orderId()));
                state.heldCount.incrementAndGet();
            } else {
                // QUEUED: no drone was idle at drop-off. Cancel right away rather than letting
                // it linger -- a lingering QUEUED order can get silently promoted by a *different*
                // slot's release (OrderQueueService.handleVehicleAvailable), which would desync
                // this harness's idea of which slot holds what from the database's real state.
                state.queuedCount.incrementAndGet();
                orderService.cancelOrder(userId, order.orderId());
                state.outstanding.decrementAndGet();
                state.idleSlots.add(userId);
            }
        } catch (Exception e) {
            state.outstanding.decrementAndGet();
            state.idleSlots.add(userId);
        }
    }

    private void release(HeldClaim held, DemandState state) {
        try {
            orderService.cancelOrder(held.userId(), held.orderId());
        } finally {
            state.outstanding.decrementAndGet();
            state.idleSlots.add(held.userId());
        }
    }

    // One test user per concurrency slot -- distinct accounts so concurrent virtual users
    // never race on the same QuoteSnapshotCache entry (it holds one snapshot per user).
    private List<Integer> registerSlotUsers(int count) {
        String runId = UUID.randomUUID().toString().substring(0, 8); // users.username is varchar(50)
        List<Integer> userIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String username = "loadtest-" + runId + "-slot-" + i;
            userService.register(username, "loadtest-password");
            userIds.add(userService.findByUsername(username).id());
        }
        return userIds;
    }
}
