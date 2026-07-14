package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.agent.orchestrator.llm.LlmUsage;
import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the in-memory execution state for NodeExecutor.
 * Centralizes all ConcurrentHashMap access patterns for execution results,
 * condition results, file registries, and run tracking.
 * Includes TTL-based eviction to prevent unbounded memory growth.
 */
@Component
public class ExecutionStateManager {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateManager.class);

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

    /** Entry with timestamp for TTL-based eviction. */
    private static class TimedEntry<V> {
        final V value;
        final long timestamp;

        TimedEntry(V value) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final Map<String, Map<String, String>> nodeResults = new ConcurrentHashMap<>();
    private final Map<String, String> conditionResults = new ConcurrentHashMap<>();
    private final Map<String, String> outputFileRegistry = new ConcurrentHashMap<>();
    private final Map<String, TimedEntry<Object>> generatedFilesRegistry = new ConcurrentHashMap<>();

    /** Max entries in generatedFilesRegistry before eviction. */
    private static final int MAX_GENERATED_FILES = 1000;
    /** TTL for generated files (24 hours). */
    private static final long GENERATED_FILE_TTL_MS = TimeUnit.HOURS.toMillis(24);
    /** TTL for node results (24 hours). */
    private static final long NODE_RESULT_TTL_MS = TimeUnit.HOURS.toMillis(24);
    /** TTL for condition results (1 hour). */
    private static final long CONDITION_RESULT_TTL_MS = TimeUnit.HOURS.toMillis(1);

    private final Map<String, String> schemaRunIds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> fileChanges = new ConcurrentHashMap<>();
    private final Map<String, List<PendingDiff>> pendingDiffRegistry = new ConcurrentHashMap<>();
    private final Map<String, LlmUsage> nodeTokenUsage = new ConcurrentHashMap<>();

    /** Background scheduler for TTL cleanup. */
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "execution-state-cleanup");
        t.setDaemon(true);
        return t;
    });

    public ExecutionStateManager() {
        // Run cleanup every hour
        cleanupScheduler.scheduleAtFixedRate(this::evictExpiredEntries, 1, 1, TimeUnit.HOURS);
    }

    @PreDestroy
    public void shutdown() {
        cleanupScheduler.shutdownNow();
    }

    private void evictExpiredEntries() {
        long now = System.currentTimeMillis();
        int evicted = 0;

        // Evict expired generated files
        evicted += evictExpired(generatedFilesRegistry, GENERATED_FILE_TTL_MS, now);
        
        // Evict expired node results
        for (Map<String, String> schemaResults : nodeResults.values()) {
            evicted += evictExpired(schemaResults, NODE_RESULT_TTL_MS, now);
        }
        
        // Evict expired condition results
        evicted += evictExpired(conditionResults, CONDITION_RESULT_TTL_MS, now);

        if (evicted > 0) {
            log.debug("ExecutionStateManager: evicted {} expired entries", evicted);
        }

        // Hard limit on generated files
        if (generatedFilesRegistry.size() > MAX_GENERATED_FILES) {
            int toRemove = generatedFilesRegistry.size() - MAX_GENERATED_FILES / 2;
            generatedFilesRegistry.keySet().stream().limit(toRemove).forEach(generatedFilesRegistry::remove);
            log.warn("generatedFilesRegistry exceeded {} entries, removed {}", MAX_GENERATED_FILES, toRemove);
        }
    }

    private <K, V> int evictExpired(Map<K, V> map, long ttlMs, long now) {
        if (map instanceof ConcurrentHashMap) {
            int count = 0;
            for (Iterator<Map.Entry<K, V>> it = map.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<K, V> entry = it.next();
                V value = entry.getValue();
                if (value instanceof TimedEntry) {
                    TimedEntry<?> timed = (TimedEntry<?>) value;
                    if (now - timed.timestamp > ttlMs) {
                        it.remove();
                        count++;
                    }
                }
            }
            return count;
        }
        return 0;
    }

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
        // Return unwrapped values for backward compatibility
        Map<String, Object> unwrapped = new HashMap<>();
        generatedFilesRegistry.forEach((k, v) -> unwrapped.put(k, v.value));
        return unwrapped;
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
        generatedFilesRegistry.put(key, new TimedEntry<>(value));
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

    /**
     * Remove all state for a schema — prevents unbounded memory growth.
     */
    public void removeSchema(String schemaId) {
        nodeResults.remove(schemaId);
        conditionResults.keySet().removeIf(k -> k.startsWith(schemaId));
        outputFileRegistry.keySet().removeIf(k -> k.startsWith(schemaId));
        generatedFilesRegistry.keySet().removeIf(k -> k.startsWith(schemaId));
        schemaRunIds.remove(schemaId);
        fileChanges.keySet().removeIf(k -> k.startsWith(schemaId + ":"));
        pendingDiffRegistry.keySet().removeIf(k -> k.startsWith(schemaId + ":"));
        nodeTokenUsage.keySet().removeIf(k -> k.startsWith(schemaId + ":"));
    }
}
