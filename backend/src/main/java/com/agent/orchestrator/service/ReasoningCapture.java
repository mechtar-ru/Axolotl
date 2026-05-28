package com.agent.orchestrator.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Captures LLM reasoning/thought content during node execution.
 * <p>
 * Strategies that call {@code llmService.chat()} or {@code llmService.streamingChat()}
 * receive an {@code LlmResponse} that may contain reasoning content. After extracting
 * the main text via {@code .text()}, they call {@link #capture(String, String)} to store
 * the reasoning for later persistence by {@link NodeRouter}.
 * <p>
 * {@link NodeRouter} calls {@link #consume(String)} after the strategy returns to
 * read and clear the stored reasoning for the given node.
 */
@Component
public class ReasoningCapture {

    private final ConcurrentMap<String, String> store = new ConcurrentHashMap<>();

    /**
     * Store reasoning content for a node.
     *
     * @param nodeId    the SchemaNode ID
     * @param reasoning the reasoning/thought content (may be null)
     */
    public void capture(String nodeId, String reasoning) {
        if (reasoning != null && !reasoning.isBlank()) {
            store.put(nodeId, reasoning);
        }
    }

    /**
     * Read and remove reasoning content for a node.
     *
     * @param nodeId the SchemaNode ID
     * @return the stored reasoning, or null if none was captured
     */
    public String consume(String nodeId) {
        return store.remove(nodeId);
    }

    /** Clear all stored reasoning (for testing or error recovery). */
    void clear() {
        store.clear();
    }
}
