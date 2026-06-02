package com.agent.orchestrator.websocket;

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ErrorCategoryTest {

    @Test
    void fromException_nullMessage_returnsINTERNAL_ERROR() {
        Exception e = new RuntimeException((String) null);
        assertEquals(ExecutionWebSocketHandler.ErrorCategory.INTERNAL_ERROR,
                ExecutionWebSocketHandler.ErrorCategory.fromException(e));
    }

    @Test
    void fromException_socketTimeout_returnsTIMEOUT() {
        Exception e = new SocketTimeoutException("Read timed out");
        assertEquals(ExecutionWebSocketHandler.ErrorCategory.TIMEOUT,
                ExecutionWebSocketHandler.ErrorCategory.fromException(e));
    }

    @Test
    void fromException_429_returnsLLM_ERROR() {
        Exception e = new RuntimeException("HTTP 429 Too Many Requests");
        assertEquals(ExecutionWebSocketHandler.ErrorCategory.LLM_ERROR,
                ExecutionWebSocketHandler.ErrorCategory.fromException(e));
    }

    @Test
    void fromException_generic_returnsINTERNAL_ERROR() {
        Exception e = new RuntimeException("generic error");
        assertEquals(ExecutionWebSocketHandler.ErrorCategory.INTERNAL_ERROR,
                ExecutionWebSocketHandler.ErrorCategory.fromException(e));
    }

    @Test
    void enumValues_haveCorrectCodes() {
        assertEquals("", ExecutionWebSocketHandler.ErrorCategory.NONE.getCode());
        assertEquals("timeout", ExecutionWebSocketHandler.ErrorCategory.TIMEOUT.getCode());
        assertEquals("llm_error", ExecutionWebSocketHandler.ErrorCategory.LLM_ERROR.getCode());
        assertEquals("tool_error", ExecutionWebSocketHandler.ErrorCategory.TOOL_ERROR.getCode());
        assertEquals("validation_error", ExecutionWebSocketHandler.ErrorCategory.VALIDATION_ERROR.getCode());
        assertEquals("internal_error", ExecutionWebSocketHandler.ErrorCategory.INTERNAL_ERROR.getCode());
        assertEquals("auth_error", ExecutionWebSocketHandler.ErrorCategory.AUTH_ERROR.getCode());
        assertEquals("permission_error", ExecutionWebSocketHandler.ErrorCategory.PERMISSION_ERROR.getCode());
    }
}
