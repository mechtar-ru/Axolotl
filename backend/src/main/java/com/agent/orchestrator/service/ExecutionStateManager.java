package com.agent.orchestrator.service;

import org.springframework.stereotype.Component;

import com.agent.orchestrator.llm.LlmUsage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the in-memory execution state for NodeExecutor.
 * Centralizes all ConcurrentHashMap access patterns for execution results,
 * condition results, file registries, and run tracking.
 */
@Component
public class ExecutionStateManager {

    public static class PendingDiff {
        public final String filePath;
        public final String originalContent;
        public final String newContent;
        public final String tempBackupPath;

        public PendingDiff(String filePath, String originalContent, String newContent, String tempBackupPath) {
            this.filePath = filePath;
            this.originalContent = originalContent;
            this.newContent = newContent;
            this.tempBackupPath = tempBackupPath;
        }
    }

    private final Map<String, Map<String, String>> nodeResults = new ConcurrentHashMap<>();
    private final Map<String, String> conditionResults = new ConcurrentHashMap<>();
    private final Map<String, String> outputFileRegistry = new ConcurrentHashMap<>();
    private final Map<String, Object> generatedFilesRegistry = new ConcurrentHashMap<>();
    private final Map<String, String> schemaRunIds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> fileChanges = new ConcurrentHashMap<>();
    private final Map<String, List<PendingDiff>> pendingDiffRegistry = new ConcurrentHashMap<>();
    private final Map<String, LlmUsage> nodeTokenUsage = new ConcurrentHashMap<>();

    /**
     * Record per-node token usage.
     * Key format: schemaId + ":" + nodeId
     */
    public void recordTokenUsage(String key, LlmUsage usage) {
        nodeTokenUsage.merge(key, usage, (existing, incoming) -> {
            existing.add(incoming);
            return existing;
        });
    }

    /**
     * Read and clear per-node token usage.
     */
    public LlmUsage getAndClearTokenUsage(String schemaId, String nodeId) {
        return nodeTokenUsage.remove(schemaId + ":" + nodeId);
    }

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

    public void recordFileChange(String schemaId, String nodeId, String path, String action) {
        String stageKey = schemaId + ":" + nodeId;
        fileChanges.computeIfAbsent(stageKey, k -> new ConcurrentHashMap<>()).put(path, action);
    }

    public Map<String, String> getFileChanges(String schemaId, String nodeId) {
        return fileChanges.getOrDefault(schemaId + ":" + nodeId, Map.of());
    }

    public Map<String, Map<String, String>> getAllFileChanges() {
        return fileChanges;
    }

    public void clearFileChanges(String schemaId, String nodeId) {
        fileChanges.remove(schemaId + ":" + nodeId);
    }

    public void addPendingDiff(String schemaId, String nodeId, PendingDiff diff) {
        String key = schemaId + ":" + nodeId;
        pendingDiffRegistry.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(diff);
    }

    public List<PendingDiff> getPendingDiffs(String schemaId, String nodeId) {
        return pendingDiffRegistry.getOrDefault(schemaId + ":" + nodeId, List.of());
    }

    public Map<String, List<PendingDiff>> getAllPendingDiffs() {
        return pendingDiffRegistry;
    }

    public void clearPendingDiffs(String schemaId, String nodeId) {
        pendingDiffRegistry.remove(schemaId + ":" + nodeId);
    }
}
