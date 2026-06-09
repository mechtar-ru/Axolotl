package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lightweight per-provider circuit breaker.
 * <p>
 * States: CLOSED (normal operation), OPEN (fail-fast), HALF_OPEN (testing recovery).
 * Tracks failures per provider name. When a provider is OPEN, throws
 * {@link CircuitBreakerOpenException} immediately without calling the provider.
 * After openDuration, transitions to HALF_OPEN allowing one probe request.
 * </p>
 */
@Component
public class CircuitBreakerWrapper {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerWrapper.class);

    private final Map<String, CircuitBreakerState> states = new ConcurrentHashMap<>();

    private final int failureThreshold;
    private final int successThreshold;
    private final long openDurationMs;

    public CircuitBreakerWrapper() {
        this(3, 2, 30_000L);
    }

    CircuitBreakerWrapper(int failureThreshold, int successThreshold, long openDurationMs) {
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.openDurationMs = openDurationMs;
    }

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String providerName) {
            super("Circuit breaker OPEN for provider: " + providerName + " — requests are being rejected");
        }
    }

    private static class CircuitBreakerState {
        final long openDurationMs;
        State state = State.CLOSED;
        int failureCount = 0;
        int successCount = 0;
        Instant openedAt = null;

        CircuitBreakerState(long openDurationMs) {
            this.openDurationMs = openDurationMs;
        }

        boolean isOpenForDuration() {
            return openedAt != null && Instant.now().isAfter(openedAt.plusMillis(openDurationMs));
        }
    }

    /**
     * Execute a supplier call through the circuit breaker for the given provider.
     * Throws {@link CircuitBreakerOpenException} if the circuit is OPEN.
     */
    public <T> T call(String providerName, Supplier<T> fn) {
        CircuitBreakerState cb = states.computeIfAbsent(providerName, k -> new CircuitBreakerState(openDurationMs));

        synchronized (cb) {
            if (cb.state == State.OPEN) {
                if (cb.isOpenForDuration()) {
                    log.info("Circuit breaker {} transitioning OPEN → HALF_OPEN (duration elapsed)", providerName);
                    cb.state = State.HALF_OPEN;
                    cb.successCount = 0;
                } else {
                    log.warn("Circuit breaker OPEN for provider: {} — fast-rejecting request", providerName);
                    throw new CircuitBreakerOpenException(providerName);
                }
            }
        }

        try {
            T result = fn.get();
            synchronized (cb) {
                cb.failureCount = 0;
                if (cb.state == State.HALF_OPEN) {
                    cb.successCount++;
                    if (cb.successCount >= successThreshold) {
                        log.info("Circuit breaker {} transitioning HALF_OPEN → CLOSED ({} successes)", providerName, cb.successCount);
                        cb.state = State.CLOSED;
                        cb.successCount = 0;
                        cb.openedAt = null;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            synchronized (cb) {
                cb.failureCount++;
                if (cb.state == State.HALF_OPEN || cb.failureCount >= failureThreshold) {
                    log.warn("Circuit breaker {} transitioning {} → OPEN (failure {}/{})",
                            providerName, cb.state, cb.failureCount, failureThreshold);
                    cb.state = State.OPEN;
                    cb.openedAt = Instant.now();
                    cb.successCount = 0;
                }
            }
            throw e;
        }
    }

    /**
     * Get the current state for a provider.
     */
    public State getState(String providerName) {
        CircuitBreakerState cb = states.get(providerName);
        if (cb == null) return State.CLOSED;
        synchronized (cb) {
            return cb.state;
        }
    }

    /**
     * Get state info for all tracked providers.
     */
    public Map<String, State> getAllStates() {
        Map<String, State> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, CircuitBreakerState> entry : states.entrySet()) {
            result.put(entry.getKey(), entry.getValue().state);
        }
        return result;
    }

    /**
     * Reset circuit breaker for a provider (e.g., after provider settings change).
     */
    public void reset(String providerName) {
        states.remove(providerName);
        log.info("Circuit breaker reset for provider: {}", providerName);
    }
}
