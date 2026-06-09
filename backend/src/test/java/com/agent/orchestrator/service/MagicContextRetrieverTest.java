package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.plugin.PluginLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MagicContextRetrieverTest {

    @Mock ToolExecutor toolExecutor;
    @Mock PluginLifecycleManager pluginManager;

    private MagicContextRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new MagicContextRetriever(toolExecutor, pluginManager);
    }

    @Test
    void isAvailable_pluginDisabled_returnsFalse() {
        when(pluginManager.isEnabled()).thenReturn(false);
        assertFalse(retriever.isAvailable());
    }

    @Test
    void isAvailable_toolsMissing_returnsFalse() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_search")).thenReturn(null);
        assertFalse(retriever.isAvailable());
    }

    @Test
    void isAvailable_allReady_returnsTrue() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(Tool.class));
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(Tool.class));
        assertTrue(retriever.isAvailable());
    }

    @Test
    void retrieveRelevantContext_pluginUnavailable_returnsEmpty() {
        when(pluginManager.isEnabled()).thenReturn(false);
        String result = retriever.retrieveRelevantContext("test query", "s1", 5);
        assertEquals("", result);
        verify(toolExecutor, never()).execute(anyString(), any(), any());
    }

    @Test
    void retrieveRelevantContext_executeFails_returnsEmpty() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(Tool.class));
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(Tool.class));
        when(toolExecutor.execute(anyString(), any(), isNull()))
                .thenReturn(ToolResult.error("Search failed"));

        String result = retriever.retrieveRelevantContext("test query", "s1", 5);
        assertEquals("", result);
    }

    @Test
    void retrieveRelevantContext_nullResult_returnsEmpty() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(Tool.class));
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(Tool.class));
        when(toolExecutor.execute(anyString(), any(), isNull())).thenReturn(null);

        String result = retriever.retrieveRelevantContext("test query", "s1", 5);
        assertEquals("", result);
    }

    @Test
    void retrieveRelevantContext_successfulPlainText_returnsFormatted() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(Tool.class));
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(Tool.class));
        when(toolExecutor.execute(anyString(), any(), isNull()))
                .thenReturn(ToolResult.ok("Found relevant memory about authentication"));

        String result = retriever.retrieveRelevantContext("authentication", "s1", 5);
        assertTrue(result.contains("Related Context from Past Runs"));
        assertTrue(result.contains("Found relevant memory about authentication"));
    }

    @Test
    void retrieveRelevantContext_successfulJsonArray_parsesAndFormats() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(Tool.class));
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(Tool.class));

        String jsonResult = "[{\"content\":\"Memory about auth flow\"},{\"content\":\"Memory about database schema\"}]";
        when(toolExecutor.execute(anyString(), any(), isNull()))
                .thenReturn(ToolResult.ok(jsonResult));

        String result = retriever.retrieveRelevantContext("auth", "s1", 5);
        assertTrue(result.contains("Result 1"));
        assertTrue(result.contains("Memory about auth flow"));
        assertTrue(result.contains("Result 2"));
        assertTrue(result.contains("Memory about database schema"));
    }

    @Test
    void retrieveRelevantContext_usesDefaultMaxResults() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(Tool.class));
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(Tool.class));
        when(toolExecutor.execute(anyString(), argThat(args -> {
            Object limit = ((Map<String, Object>) args).get("limit");
            return limit != null && (int) limit == 5; // DEFAULT_MAX_RESULTS
        }), isNull()))
                .thenReturn(ToolResult.ok("Result"));

        // Use 2-arg overload (no maxResults specified)
        String result = retriever.retrieveRelevantContext("query", "s1");
        assertTrue(result.contains("Related Context"));
    }
}
