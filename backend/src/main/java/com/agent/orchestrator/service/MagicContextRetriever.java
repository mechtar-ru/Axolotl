package com.agent.orchestrator.service;

import com.agent.orchestrator.plugin.PluginLifecycleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retrieves relevant context from Magic Context via RAG search before a node starts.
 * <p>
 * Queries Magic Context with a semantic search using the node's task description
 * and returns the top results as a formatted text block. Falls back to empty string
 * when Magic Context is not available.
 */
@Component
public class MagicContextRetriever {

    private static final Logger log = LoggerFactory.getLogger(MagicContextRetriever.class);

    private final ToolExecutor toolExecutor;
    private final PluginLifecycleManager pluginManager;

    /** Default number of search results */
    private static final int DEFAULT_MAX_RESULTS = 5;

    /** Max characters per individual result */
    private static final int MAX_RESULT_CHARS = 2000;

    public MagicContextRetriever(ToolExecutor toolExecutor, PluginLifecycleManager pluginManager) {
        this.toolExecutor = toolExecutor;
        this.pluginManager = pluginManager;
    }

    /**
     * Retrieve relevant context from Magic Context for the given query.
     *
     * @param query      semantic search query (built from node prompt + type + schema context)
     * @param schemaId   schema ID to scope results
     * @param maxResults max results to return (default: {@value #DEFAULT_MAX_RESULTS})
     * @return formatted context block, or empty string if unavailable or no results
     */
    public String retrieveRelevantContext(String query, String schemaId, int maxResults) {
        if (!isAvailable()) return "";

        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("query", buildSearchQuery(query, schemaId));
            args.put("limit", maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS);

            var result = toolExecutor.execute("ctx_search", args, null);

            if (result == null || !result.isSuccess()) {
                log.debug("MC search returned no results for: {}", query);
                return "";
            }

            String output = result.getOutput();
            if (output == null || output.isBlank()) return "";

            return formatResults(output);

        } catch (Exception e) {
            log.debug("MC retrieval failed for query '{}': {}", query, e.getMessage());
            return "";
        }
    }

    /**
     * Convenience overload with default max results.
     */
    public String retrieveRelevantContext(String query, String schemaId) {
        return retrieveRelevantContext(query, schemaId, DEFAULT_MAX_RESULTS);
    }

    /**
     * Check if Magic Context plugin is available.
     */
    public boolean isAvailable() {
        try {
            return pluginManager.isEnabled()
                    && toolExecutor.getTool("ctx_search") != null
                    && toolExecutor.getTool("ctx_memory") != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Internals ───

    /**
     * Build a search query that includes schema context for better relevance.
     */
    private String buildSearchQuery(String query, String schemaId) {
        if (schemaId != null && !schemaId.isBlank()) {
            return query + " schema:" + schemaId;
        }
        return query;
    }

    /**
     * Format raw search results into a readable context block.
     * The output from ctx_search is expected to be a JSON string or
     * a formatted list of results.
     */
    private String formatResults(String raw) {
        // If the result is already a formatted string (plain text list), use as-is
        if (!raw.trim().startsWith("[")) {
            return "=== Related Context from Past Runs ===\n" + truncateResult(raw) + "\n";
        }

        // If it's JSON array, format each item
        StringBuilder sb = new StringBuilder();
        sb.append("=== Related Context from Past Runs ===\n");

        try {
            // Simple JSON array parsing — split at "},{" boundaries
            String items = raw.trim();
            if (items.startsWith("[") && items.endsWith("]")) {
                items = items.substring(1, items.length() - 1);
            }

            String[] parts = items.split("\\},\\{");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                // Strip leading/trailing braces added by split
                if (!part.startsWith("{")) part = "{" + part;
                if (!part.endsWith("}")) part = part + "}";

                // Extract content field (simple heuristic — avoids full JSON parse)
                String content = extractJsonField(part, "content");
                if (content != null && !content.isBlank()) {
                    sb.append("--- Result ").append(i + 1).append(" ---\n");
                    sb.append(truncateResult(content)).append("\n");
                }
            }
        } catch (Exception e) {
            // Fallback: use raw output
            sb.append(truncateResult(raw));
        }

        return sb.append("=== End Related Context ===\n").toString();
    }

    /**
     * Truncate a single result to avoid context bloat.
     */
    private String truncateResult(String text) {
        if (text.length() <= MAX_RESULT_CHARS) return text;
        return text.substring(0, MAX_RESULT_CHARS) + "\n... [truncated]";
    }

    /**
     * Extract a JSON string field value with a simple heuristic parser.
     * Handles escaped quotes within values.
     */
    private String extractJsonField(String json, String field) {
        String search = "\"" + field + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                value.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.length() > 0 ? value.toString() : null;
    }
}
