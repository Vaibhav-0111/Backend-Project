package com.example.webhook.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BackoffCalculator — verifies the decorrelated jitter formula
 * boundaries: attempt 0, attempt at max, backoff must increase in bounds.
 */
class BackoffCalculatorTest {

    private final BackoffCalculator calculator = new BackoffCalculator();

    @Test
    void firstAttemptIsAtLeastBase() {
        long delay = calculator.calculateDelayDeterministic(1);
        assertThat(delay).isGreaterThanOrEqualTo(calculator.getBaseSeconds());
    }

    @Test
    void delayNeverExceedsCap() {
        for (int i = 1; i <= calculator.getMaxAttempts(); i++) {
            long delay = calculator.calculateDelayDeterministic(i);
            assertThat(delay)
                .as("Attempt %d should not exceed cap", i)
                .isLessThanOrEqualTo(calculator.getCapSeconds());
        }
    }

    @Test
    void delayIncreasesBetweenAttempts() {
        // Using deterministic variant — delays should grow monotonically
        long prev = calculator.calculateDelayDeterministic(1);
        for (int i = 2; i <= calculator.getMaxAttempts(); i++) {
            long curr = calculator.calculateDelayDeterministic(i);
            assertThat(curr)
                .as("Attempt %d delay should be >= attempt %d delay (deterministic)", i, i - 1)
                .isGreaterThanOrEqualTo(prev);
            prev = curr;
        }
    }

    @Test
    void maxAttemptsReachedAtThreshold() {
        assertThat(calculator.isMaxAttemptsReached(calculator.getMaxAttempts())).isTrue();
        assertThat(calculator.isMaxAttemptsReached(calculator.getMaxAttempts() - 1)).isFalse();
    }

    @Test
    void randomDelayAlwaysWithinBounds() {
        // Run 100 random samples, all must be within [base, cap]
        for (int attempt = 1; attempt <= 8; attempt++) {
            for (int sample = 0; sample < 100; sample++) {
                long delay = calculator.calculateDelay(attempt);
                assertThat(delay)
                    .as("Random delay for attempt %d should be in bounds", attempt)
                    .isGreaterThanOrEqualTo(calculator.getBaseSeconds())
                    .isLessThanOrEqualTo(calculator.getCapSeconds());
            }
        }
    }
}
