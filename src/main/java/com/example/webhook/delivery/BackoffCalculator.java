package com.example.webhook.delivery;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Decorrelated jitter backoff calculator.
 * 
 * Formula: sleep = min(cap, random_between(base, previous_sleep * 3))
 * 
 * This is the AWS-style decorrelated jitter algorithm. Unlike naive exponential
 * backoff (base * 2^attempt), this spreads retries to avoid thundering herd
 * when many deliveries to the same endpoint fail simultaneously.
 * 
 * The key property: the *bound* increases with each attempt (previous_sleep * 3 grows),
 * but individual values are not strictly monotonic — that's by design, it's what
 * spreads load across time windows.
 * 
 * Parameters:
 *   base = 30 seconds (minimum wait)
 *   cap  = 4 hours (14400 seconds, maximum wait)
 *   maxAttempts = 8
 * 
 * Approximate schedule across 8 attempts:
 *   Attempt 1: 30s - 90s
 *   Attempt 2: 30s - 270s (~4.5 min)
 *   Attempt 3: 30s - 810s (~13.5 min)
 *   Attempt 4: 30s - 2430s (~40.5 min)
 *   Attempt 5: 30s - 7290s (~2h) → capped at 4h
 *   Attempt 6-8: 30s - 14400s (4h cap)
 * 
 * Total retry window: roughly 24 hours worst-case.
 */
@Component
public class BackoffCalculator {

    private static final long BASE_SECONDS = 30;
    private static final long CAP_SECONDS = 4 * 3600; // 4 hours
    public static final int MAX_ATTEMPTS = 8;

    /**
     * Calculate next retry delay in seconds using decorrelated jitter.
     * 
     * @param attemptNumber the attempt just completed (1-based)
     * @return delay in seconds before next attempt
     */
    public long calculateDelay(int attemptNumber) {
        long prevSleep = BASE_SECONDS;
        for (int i = 1; i < attemptNumber; i++) {
            prevSleep = Math.min(CAP_SECONDS, randomBetween(BASE_SECONDS, prevSleep * 3));
        }
        return Math.min(CAP_SECONDS, randomBetween(BASE_SECONDS, prevSleep * 3));
    }

    /**
     * Calculate delay with a fixed seed for testing — allows asserting bound growth.
     */
    public long calculateDelayDeterministic(int attemptNumber) {
        long prevSleep = BASE_SECONDS;
        for (int i = 1; i < attemptNumber; i++) {
            // Use midpoint instead of random for deterministic tests
            prevSleep = Math.min(CAP_SECONDS, (BASE_SECONDS + prevSleep * 3) / 2);
        }
        return Math.min(CAP_SECONDS, (BASE_SECONDS + prevSleep * 3) / 2);
    }

    public boolean isMaxAttemptsReached(int attemptCount) {
        return attemptCount >= MAX_ATTEMPTS;
    }

    public long getBaseSeconds() {
        return BASE_SECONDS;
    }

    public long getCapSeconds() {
        return CAP_SECONDS;
    }

    public int getMaxAttempts() {
        return MAX_ATTEMPTS;
    }

    private long randomBetween(long low, long high) {
        if (high <= low) return low;
        return ThreadLocalRandom.current().nextLong(low, high);
    }
}
