package com.agent.orchestrator.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PluginBridge internal logic that can be exercised
 * without spawning a real Bun process.
 * <p>
 * Full integration tests (start/stop/restart) require a Bun installation
 * and are covered by the project's e2e test suite.
 */
class PluginBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── Constructor ───

    @Test
    void constructorSetsFields() {
        PluginBridge bridge = new PluginBridge("test", "bun", "/path/bridge.js",
                5000, 10000, 3, 1000);
        assertEquals("test", bridge.getName());
        assertNull(bridge.getPluginId());
        assertNull(bridge.getPluginVersion());
        assertFalse(bridge.isRunning());
        assertEquals(0, bridge.getUptimeMs());
        assertNull(bridge.getProcess());
    }

    // ─── sendRaw without stdin ───

    @Test
    void sendRawReturnsFalseWhenStdinNull() {
        PluginBridge bridge = makeBridge();
        assertFalse(bridge.sendRaw("test"));
    }

    // ─── sendRaw with real stdin writer ───

    @Test
    void sendRawWritesToStdin() throws Exception {
        PluginBridge bridge = makeBridge();
        PipedInputStream pipeInput = new PipedInputStream();
        PipedOutputStream pipeOutput = new PipedOutputStream(pipeInput);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(pipeOutput));

        setField(bridge, "stdin", writer);

        assertTrue(bridge.sendRaw("{\"test\":true}\n"));

        writer.flush();
        byte[] buf = new byte[128];
        int len = pipeInput.read(buf);
        String written = new String(buf, 0, len);
        assertTrue(written.contains("test"));
    }

    // ─── sendRaw with broken stdin ───

    @Test
    void sendRawReturnsFalseOnIOException() throws Exception {
        PluginBridge bridge = makeBridge();
        BufferedWriter brokenWriter = new BufferedWriter(new OutputStreamWriter(
                new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        throw new IOException("broken pipe");
                    }
                }
        ));

        setField(bridge, "stdin", brokenWriter);

        assertFalse(bridge.sendRaw("test"));
    }

    // ─── PluginException ───

    @Test
    void pluginExceptionMessage() {
        PluginBridge.PluginException e = new PluginBridge.PluginException("test error");
        assertEquals("test error", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void pluginExceptionWithCause() {
        Throwable cause = new RuntimeException("root cause");
        PluginBridge.PluginException e = new PluginBridge.PluginException("wrapped", cause);
        assertEquals("wrapped", e.getMessage());
        assertSame(cause, e.getCause());
    }

    // ─── handleMessage routing (via startStdoutReader's parser) ───
    // We test handleMessage indirectly by verifying ParsedMessage routing
    // The actual handleMessage is private; we test the logic through
    // the ParsedMessage type system that drives it.

    @Test
    void responseMessageRouting() {
        PluginMessage.ParsedMessage response = PluginMessage.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}");
        assertTrue(response.isResponse());
        assertFalse(response.isError());
        assertEquals(1, response.id().intValue());
    }

    @Test
    void errorResponseMessageRouting() {
        PluginMessage.ParsedMessage error = PluginMessage.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-1,\"message\":\"fail\"}}");
        assertTrue(error.isResponse()); // error responses are technically responses
        assertTrue(error.isError());
    }

    @Test
    void requestMessageRouting() {
        PluginMessage.ParsedMessage req = PluginMessage.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tool/execute\",\"params\":{}}");
        assertTrue(req.isRequest());
        assertEquals("tool/execute", req.method());
    }

    @Test
    void notificationMessageRouting() {
        PluginMessage.ParsedMessage notif = PluginMessage.parse(
                "{\"jsonrpc\":\"2.0\",\"method\":\"plugin/ready\"}");
        assertTrue(notif.isNotification());
        assertEquals("plugin/ready", notif.method());
    }

    @Test
    void invalidMessageIsNotRouted() {
        PluginMessage.ParsedMessage invalid = PluginMessage.parse("{bad json");
        assertTrue(invalid.isInvalid());
    }

    // ─── Helpers ───

    private PluginBridge makeBridge() {
        return new PluginBridge("test", "bun", "/path/bridge.js",
                5000, 10000, 3, 1000);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
