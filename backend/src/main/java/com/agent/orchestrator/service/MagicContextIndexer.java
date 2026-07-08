package com.agent.orchestrator.service;

import com.agent.orchestrator.plugin.PluginLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Auto-indexes node outputs into Magic Context after each node completes.
 * <p>
 * Runs asynchronously on a dedicated thread pool — does not block the pipeline.
 * Falls back silently when Magic Context is not available.
 */
@Component
public class MagicContextIndexer {

    private static final Logger log = LoggerFactory.getLogger(MagicContextIndexer.class);

    private final ToolExecutor toolExecutor;
    private final PluginLifecycleManager pluginManager;
    private final ExecutorService executor;

    /** Maximum output length indexed to MC (avoids bloating the memory store) */
    private static final int MAX_OUTPUT_CHARS = 10_000;

    public MagicContextIndexer(ToolExecutor toolExecutor, PluginLifecycleManager pluginManager) {
        this.toolExecutor = toolExecutor;
        this.pluginManager = pluginManager;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mc-indexer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Index a node's output into Magic Context for future RAG retrieval.
     * Non-blocking — returns immediately, runs async.
     */
    public void indexNodeOutput(String schemaId, String nodeId, String nodeType,
                                String output, String schemaName, String nodeName) {
        if (!isAvailable()) return;

        String trimmed = output != null && output.length() > MAX_OUTPUT_CHARS
                ? output.substring(0, MAX_OUTPUT_CHARS) + "\n... [truncated]"
                : output;
        if (trimmed == null || trimmed.isBlank()) return;

        executor.submit(() -> {
            try {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("action", "write");
                args.put("category", "node_output");
                args.put("content", trimmed);

                // Store with rich tags for search
                Map<String, Object> tags = Map.of(
                        "schemaId", schemaId != null ? schemaId : "",
                        "nodeId", nodeId != null ? nodeId : "",
                        "nodeType", nodeType != null ? nodeType : "",
                        "schemaName", schemaName != null ? schemaName : "",
                        "nodeName", nodeName != null ? nodeName : ""
                );
                // Encode tags as part of content for full-text search
                String taggedContent = "[schema:" + schemaId + "][node:" + nodeId
                        + "][type:" + nodeType + "][name:" + nodeName + "]\n" + trimmed;
                args.put("content", taggedContent);

                toolExecutor.execute("ctx_memory", args, null);
                log.debug("Indexed node {} ({}) output to MC ({} chars)", nodeId, nodeType, trimmed.length());
            } catch (Exception e) {
                log.debug("Failed to index node output to MC: {}", e.getMessage());
            }
        });
    }

    /**
     * Check if Magic Context plugin is available.
     */
    public boolean isAvailable() {
        try {
            return pluginManager.isEnabled()
                    && toolExecutor.getTool("ctx_memory") != null
                    && toolExecutor.getTool("ctx_search") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Shutdown the executor — called by Spring lifecycle. */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
