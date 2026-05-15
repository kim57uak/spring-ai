package com.example.springsupervisorai.service.resilience;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerUtilsTest {

    @Test
    public void testCircuitBreakerClosedState() {
        CircuitBreakerUtils.CircuitBreaker circuitBreaker = new CircuitBreakerUtils.CircuitBreaker(3, 1000);
        assertEquals(CircuitBreakerUtils.CircuitState.CLOSED, circuitBreaker.getState());
    }

    @Test
    public void testCircuitBreakerOpenState() {
        CircuitBreakerUtils.CircuitBreaker circuitBreaker = new CircuitBreakerUtils.CircuitBreaker(1, 1000);
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        assertEquals(CircuitBreakerUtils.CircuitState.OPEN, circuitBreaker.getState());
        assertThrows(CircuitBreakerUtils.CircuitBreakerOpenException.class, () -> {
            circuitBreaker.execute(() -> "Should not execute");
        });
    }

    @Test
    public void testCircuitBreakerHalfOpenState() throws InterruptedException {
        CircuitBreakerUtils.CircuitBreaker circuitBreaker = new CircuitBreakerUtils.CircuitBreaker(1, 100);
        // Open the circuit breaker
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        // Wait for the timeout to expire
        Thread.sleep(150);
        // After timeout, execute() enters HALF_OPEN, supplier throws → re-opens
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        assertEquals(CircuitBreakerUtils.CircuitState.OPEN, circuitBreaker.getState());
    }

    @Test
    public void testCircuitBreakerRecovery() throws InterruptedException {
        CircuitBreakerUtils.CircuitBreaker circuitBreaker = new CircuitBreakerUtils.CircuitBreaker(1, 100);
        // Open the circuit breaker
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        // Wait for the timeout to expire
        Thread.sleep(150);
        // After timeout, execute() enters HALF_OPEN
        // Execute successfully to close the circuit breaker
        circuitBreaker.execute(() -> "Success");
        assertEquals(CircuitBreakerUtils.CircuitState.CLOSED, circuitBreaker.getState());
    }

    @Test
    public void testCircuitBreakerFailureThreshold() {
        CircuitBreakerUtils.CircuitBreaker circuitBreaker = new CircuitBreakerUtils.CircuitBreaker(3, 1000);
        // First failure
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        assertEquals(CircuitBreakerUtils.CircuitState.CLOSED, circuitBreaker.getState());
        // Second failure
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        assertEquals(CircuitBreakerUtils.CircuitState.CLOSED, circuitBreaker.getState());
        // Third failure - should open the circuit breaker
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        assertEquals(CircuitBreakerUtils.CircuitState.OPEN, circuitBreaker.getState());
    }

    @Test
    public void testCircuitBreakerSuccessAfterFailure() {
        CircuitBreakerUtils.CircuitBreaker circuitBreaker = new CircuitBreakerUtils.CircuitBreaker(3, 1000);
        // First failure
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        // Success - should reset failure count
        circuitBreaker.execute(() -> "Success");
        // Second failure
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        // Third failure - should not open the circuit breaker yet
        assertThrows(RuntimeException.class, () -> {
            circuitBreaker.execute(() -> {
                throw new RuntimeException("Test exception");
            });
        });
        assertEquals(CircuitBreakerUtils.CircuitState.CLOSED, circuitBreaker.getState());
    }
}