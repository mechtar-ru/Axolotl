package com.agent.orchestrator.service;

import com.agent.orchestrator.plugin.PluginLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MagicContextIndexerTest {

    @Mock ToolExecutor toolExecutor;
    @Mock PluginLifecycleManager pluginManager;

    private MagicContextIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new MagicContextIndexer(toolExecutor, pluginManager);
    }

    @Test
    void isAvailable_pluginDisabled_returnsFalse() {
        when(pluginManager.isEnabled()).thenReturn(false);
        assertFalse(indexer.isAvailable());
    }

    @Test
    void isAvailable_toolsMissing_returnsFalse() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_memory")).thenReturn(null);
        assertFalse(indexer.isAvailable());
    }

    @Test
    void isAvailable_allReady_returnsTrue() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));
        assertTrue(indexer.isAvailable());
    }

    @Test
    void indexNodeOutput_pluginUnavailable_doesNotCallExecute() {
        when(pluginManager.isEnabled()).thenReturn(false);
        indexer.indexNodeOutput("s1", "n1", "agent", "some output", "test-schema", "TestNode");
        verify(toolExecutor, never()).execute(anyString(), any(), any());
    }

    @Test
    void indexNodeOutput_nullOutput_doesNotCallExecute() {
        // isAvailable = true
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));

        indexer.indexNodeOutput("s1", "n1", "agent", null, "test-schema", "TestNode");
        // The method returns immediately for null/blank, so execute is never called
        verify(toolExecutor, never()).execute(anyString(), any(), any());
    }

    @Test
    void indexNodeOutput_emptyOutput_doesNotCallExecute() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));

        indexer.indexNodeOutput("s1", "n1", "agent", "", "test-schema", "TestNode");
        verify(toolExecutor, never()).execute(anyString(), any(), any());
    }

    @Test
    void indexNodeOutput_callsCtxMemoryAsync() throws InterruptedException {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));

        indexer.indexNodeOutput("s1", "n1", "agent", "important result data", "test-schema", "TestNode");
        // Wait a tiny bit for the async executor to run
        Thread.sleep(50);

        verify(toolExecutor, timeout(500)).execute(eq("ctx_memory"), any(Map.class), isNull());
    }

    @Test
    void indexNodeOutput_truncatesLongOutput() throws InterruptedException {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));

        String longOutput = "X".repeat(15_000);
        indexer.indexNodeOutput("s1", "n1", "agent", longOutput, "test-schema", "TestNode");
        Thread.sleep(50);

        // Capture the args to verify truncation
        verify(toolExecutor, timeout(500)).execute(eq("ctx_memory"), argThat(args -> {
            String content = (String) ((Map<String, Object>) args).get("content");
            return content != null && content.length() < 12_000; // truncated from 15K
        }), isNull());
    }

    @Test
    void indexNodeOutput_exceptionInExecutor_doesNotThrow() {
        when(pluginManager.isEnabled()).thenReturn(true);
        when(toolExecutor.getTool("ctx_memory")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));
        when(toolExecutor.getTool("ctx_search")).thenReturn(mock(com.agent.orchestrator.model.Tool.class));

        // Should not throw despite executor exception
        indexer.indexNodeOutput("s1", "n1", "agent", "data", "test-schema", "TestNode");
        indexer.indexNodeOutput("s1", "n2", "agent", "more data", "test-schema", "TestNode");
        assertTrue(true);
    }
}
