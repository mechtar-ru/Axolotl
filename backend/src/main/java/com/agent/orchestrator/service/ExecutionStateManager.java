package com.agent.orchestrator.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the in-memory execution state for NodeExecutor.
 * Centralizes all ConcurrentHashMap access patterns for execution results,
 * condition results, file registries, and run tracking.
 */
@Component
public class ExecutionStateManager {

    private final Map<String, Map<String, String>> nodeResults = new ConcurrentHashMap<>();
    private final Map<String, String> conditionResults = new ConcurrentHashMap<>();
    private final Map<String, String> outputFileRegistry = new ConcurrentHashMap<>();
    private final Map<String, Object> generatedFilesRegistry = new ConcurrentHashMap<>();
    private final Map<String, String> schemaRunIds = new ConcurrentHashMap<>();

    public Map<String, Map<String, String>> getNodeResults() {
        return nodeResults;
    }

    public Map<String, String> getConditionResults() {
        return conditionResults;
    }

    public Map<String, String> getOutputFileRegistry() {
        return outputFileRegistry;
    }

    public Map<String, Object> getGeneratedFilesRegistry() {
        return generatedFilesRegistry;
    }

    public void setCurrentRunId(String schemaId, String runId) {
        schemaRunIds.put(schemaId, runId);
    }

    public String getCurrentRunId(String schemaId) {
        return schemaRunIds.get(schemaId);
    }

    public void removeRunId(String schemaId) {
        schemaRunIds.remove(schemaId);
    }

    public void clearNodeResults(String schemaId) {
        nodeResults.remove(schemaId);
    }

    public void putNodeResult(String schemaId, String nodeId, String result) {
        nodeResults.computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                .put(nodeId, result);
    }

    public void clearConditionResults(String schemaIdPrefix) {
        conditionResults.keySet().removeIf(k -> k.startsWith(schemaIdPrefix));
    }

    public void putConditionResult(String key, String result) {
        conditionResults.put(key, result);
    }

    public void putOutputFile(String key, String filePath) {
        outputFileRegistry.put(key, filePath);
    }

    public void putGeneratedFile(String key, Object value) {
        generatedFilesRegistry.put(key, value);
    }
}
