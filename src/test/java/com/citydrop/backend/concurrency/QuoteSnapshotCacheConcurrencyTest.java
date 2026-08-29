package com.citydrop.backend.concurrency;

import com.citydrop.backend.cache.QuoteSnapshot;
import com.citydrop.backend.cache.QuoteSnapshotCache;
import com.citydrop.backend.models.responses.DeliveryQuote;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real Redis (see application.yaml) -- this exercises the actual SETNX/Lua lock in
// QuoteSnapshotCache.put(), not a mock, since a mock can't demonstrate real contention.
@Tag("concurrency")
@SpringBootTest
class QuoteSnapshotCacheConcurrencyTest {

    @Autowired private QuoteSnapshotCache cache;

    private static final int CONCURRENT_PUTS = 10;

    @RepeatedTest(value = 50, name = "attempt {currentRepetition}/{totalRepetitions}")
    void concurrentPutsForTheSameUserNeverLoseAnEntry() throws Exception {
        int userId = 900_000 + new Random().nextInt(100_000); // scratch id, not a real registered user
        cache.evict(userId);

        List<DeliveryQuote> quotes = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_PUTS; i++) {
            // distinct stationId -> distinct cache identity, so a lost write is unambiguous
            quotes.add(new DeliveryQuote("1 Main St, San Francisco", 4.0, "ROBOT", 10.0 + i, 18.0, i + 1, true));
        }

        CountDownLatch ready = new CountDownLatch(CONCURRENT_PUTS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        List<Future<?>> futures = new ArrayList<>();
        for (DeliveryQuote quote : quotes) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                cache.put(userId, List.of(quote));
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        List<DeliveryQuote> stored = cache.get(userId).quotes().stream()
                .map(QuoteSnapshot.TimedQuote::quote)
                .toList();

        assertEquals(CONCURRENT_PUTS, stored.size(),
                "every concurrently-put quote should survive -- none lost to an unlocked read-modify-write");
        for (DeliveryQuote quote : quotes) {
            assertTrue(stored.contains(quote), "missing quote for station " + quote.stationId());
        }

        cache.evict(userId);
    }
}
