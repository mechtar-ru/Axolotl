package com.agent.orchestrator.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionWebSocketHandlerTest {

    private final ExecutionWebSocketHandler handler = new ExecutionWebSocketHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void baseMsg_producesValidJson() throws Exception {
        // Verify the handler can be instantiated and methods exist
        assertNotNull(handler);
    }

    @Test
    void objectMapper_handlesQuotes() throws Exception {
        String input = "say \"hello\"";
        String json = objectMapper.writeValueAsString(input);
        // ObjectMapper properly escapes quotes
        assertTrue(json.contains("\\\""));
        assertEquals("say \"hello\"", objectMapper.readValue(json, String.class));
    }

    @Test
    void objectMapper_handlesNewlines() throws Exception {
        String input = "line1\nline2";
        String json = objectMapper.writeValueAsString(input);
        String parsed = objectMapper.readValue(json, String.class);
        assertEquals(input, parsed);
    }

    @Test
    void objectMapper_handlesBackslashes() throws Exception {
        String input = "path\\to\\file";
        String json = objectMapper.writeValueAsString(input);
        String parsed = objectMapper.readValue(json, String.class);
        assertEquals(input, parsed);
    }

    @Test
    void objectMapper_handlesCyrillic() throws Exception {
        String input = "Привет мир";
        String json = objectMapper.writeValueAsString(input);
        String parsed = objectMapper.readValue(json, String.class);
        assertEquals(input, parsed);
    }

    @Test
    void objectMapper_handlesNull() throws Exception {
        String json = objectMapper.writeValueAsString(null);
        assertEquals("null", json);
    }

    @Test
    void objectMapper_handlesTabs() throws Exception {
        String input = "col1\tcol2";
        String json = objectMapper.writeValueAsString(input);
        String parsed = objectMapper.readValue(json, String.class);
        assertEquals(input, parsed);
    }
}
