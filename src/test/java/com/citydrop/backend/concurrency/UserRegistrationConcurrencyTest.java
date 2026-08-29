package com.citydrop.backend.concurrency;

import com.citydrop.backend.user.UserService;
import com.citydrop.backend.user.UsernameTakenException;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

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
class UserRegistrationConcurrencyTest {

    @Autowired private UserService userService;

    private static final int CONCURRENT_REGISTRATIONS = 10;

    @RepeatedTest(value = 50, name = "attempt {currentRepetition}/{totalRepetitions}")
    void onlyOneOfManyConcurrentRegistrationsWithTheSameUsernameSucceeds() throws Exception {
        String username = "dupe-" + UUID.randomUUID().toString().substring(0, 8);

        CountDownLatch ready = new CountDownLatch(CONCURRENT_REGISTRATIONS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        List<Future<Throwable>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REGISTRATIONS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    userService.register(username, "test-password");
                    return null; // success
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        ready.await();
        go.countDown();

        List<Throwable> failures = new ArrayList<>();
        int successes = 0;
        for (Future<Throwable> f : futures) {
            Throwable result = f.get(10, TimeUnit.SECONDS);
            if (result == null) successes++;
            else failures.add(result);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, successes, "exactly one concurrent registration should win the username");
        assertEquals(CONCURRENT_REGISTRATIONS - 1, failures.size());

        // Documents current behavior, not just desired behavior: UserService.register does an
        // app-level userExists() check with no transaction around it, so most losers are caught
        // as UsernameTakenException -- but a loser that passed the check before the winner
        // committed hits the DB's UNIQUE constraint directly and isn't translated. If that gap
        // (see UserService.register) is ever fixed, tighten this to require UsernameTakenException only.
        assertTrue(failures.stream().allMatch(t ->
                        t instanceof UsernameTakenException || t instanceof DataIntegrityViolationException),
                "unexpected failure type(s): " + failures);
    }
}
