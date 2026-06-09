package com.agent.orchestrator.service;

import com.agent.orchestrator.service.CircuitBreakerWrapper.CircuitBreakerOpenException;
import com.agent.orchestrator.service.CircuitBreakerWrapper.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerWrapperTest {

    private static final int FAILURE_THRESHOLD = 2;
    private static final int SUCCESS_THRESHOLD = 1;
    private static final long SHORT_DURATION_MS = 50L;

    private CircuitBreakerWrapper cb;

    @BeforeEach
    void setUp() {
        cb = new CircuitBreakerWrapper(FAILURE_THRESHOLD, SUCCESS_THRESHOLD, SHORT_DURATION_MS);
    }

    @Test
    void call_succeeds_staysClosed() {
        String result = cb.call("test-provider", () -> "ok");
        assertEquals("ok", result);
        assertEquals(State.CLOSED, cb.getState("test-provider"));
    }

    @Test
    void call_failsBelowThreshold_staysClosed() {
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail"); }));
        assertEquals(State.CLOSED, cb.getState("test-provider"));
    }

    @Test
    void call_failsAtThreshold_transitionsToOpen() {
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail1"); }));
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail2"); }));
        assertEquals(State.OPEN, cb.getState("test-provider"));
    }

    @Test
    void call_whenOpen_throwsCircuitBreakerOpenException() {
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail1"); }));
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail2"); }));

        assertThrows(CircuitBreakerOpenException.class, () ->
            cb.call("test-provider", () -> "should not reach"));
    }

    @Test
    void call_afterOpenDuration_transitionsToHalfOpen() throws Exception {
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail1"); }));
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail2"); }));
        assertEquals(State.OPEN, cb.getState("test-provider"));

        Thread.sleep(SHORT_DURATION_MS + 20);

        String result = cb.call("test-provider", () -> "recovered");
        assertEquals("recovered", result);
        assertEquals(State.CLOSED, cb.getState("test-provider"));
    }

    @Test
    void call_halfOpenFails_goesBackToOpen() throws Exception {
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail1"); }));
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail2"); }));

        Thread.sleep(SHORT_DURATION_MS + 20);

        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("half-open-fail"); }));
        assertEquals(State.OPEN, cb.getState("test-provider"));
    }

    @Test
    void reset_removesState() {
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail1"); }));
        assertThrows(RuntimeException.class, () ->
            cb.call("test-provider", () -> { throw new RuntimeException("fail2"); }));
        assertEquals(State.OPEN, cb.getState("test-provider"));

        cb.reset("test-provider");
        assertEquals(State.CLOSED, cb.getState("test-provider"));

        String result = cb.call("test-provider", () -> "ok-after-reset");
        assertEquals("ok-after-reset", result);
    }

    @Test
    void getState_unknownProvider_returnsClosed() {
        assertEquals(State.CLOSED, cb.getState("unknown"));
    }

    @Test
    void getAllStates_returnsAllTrackedProviders() {
        cb.call("p1", () -> "ok");
        cb.call("p2", () -> "ok");

        var states = cb.getAllStates();
        assertEquals(2, states.size());
        assertEquals(State.CLOSED, states.get("p1"));
        assertEquals(State.CLOSED, states.get("p2"));
    }
}
