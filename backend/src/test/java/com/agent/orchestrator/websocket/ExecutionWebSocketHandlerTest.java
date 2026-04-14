package com.agent.orchestrator.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionWebSocketHandlerTest {

    private final ExecutionWebSocketHandler handler = new ExecutionWebSocketHandler();

    @Test
    void escapeJson_normalString() {
        String result = handler.escapeJsonPublic("hello world");
        assertEquals("hello world", result);
    }

    @Test
    void escapeJson_withQuotes() {
        String result = handler.escapeJsonPublic("say \"hello\"");
        assertEquals("say \\\"hello\\\"", result);
    }

    @Test
    void escapeJson_withNewlines() {
        String result = handler.escapeJsonPublic("line1\nline2");
        assertEquals("line1\\nline2", result);
    }

    @Test
    void escapeJson_withBackslashes() {
        String result = handler.escapeJsonPublic("path\\to\\file");
        assertEquals("path\\\\to\\\\file", result);
    }

    @Test
    void escapeJson_withTabs() {
        String result = handler.escapeJsonPublic("col1\tcol2");
        assertEquals("col1\\tcol2", result);
    }

    @Test
    void escapeJson_null() {
        String result = handler.escapeJsonPublic(null);
        assertEquals("", result);
    }

    @Test
    void escapeJson_cyrillicPreserved() {
        String result = handler.escapeJsonPublic("Привет мир");
        assertEquals("Привет мир", result);
    }
}
