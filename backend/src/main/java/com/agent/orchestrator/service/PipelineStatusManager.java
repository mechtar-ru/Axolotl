package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PipelineStatusManager {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusManager.class);

    /** Max schemas with stored stage results before eviction. */
    private static final int MAX_STAGE_RESULT_SCHEMAS = 50;

    private final ConcurrentHashMap<String, CompletableFuture<?>> runningPipelines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> stageResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pipelineResumeState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> pipelineResumeTimestamps = new ConcurrentHashMap<>();

    /** Returns an unmodifiable view. Callers cannot mutate internal state. */
    public Map<String, CompletableFuture<?>> getRunningPipelines() {
        return Collections.unmodifiableMap(runningPipelines);
    }

    /** Returns an unmodifiable view. Callers cannot mutate internal state. */
    public Map<String, AtomicBoolean> getCancelFlags() {
        return Collections.unmodifiableMap(cancelFlags);
    }

    /** Returns an unmodifiable view. Callers cannot mutate internal state. */
    public Map<String, Map<String, String>> getStageResults() {
        return Collections.unmodifiableMap(stageResults);
    }

    /** Returns an unmodifiable view. Callers cannot mutate internal state. */
    public Map<String, Integer> getPipelineResumeState() {
        return Collections.unmodifiableMap(pipelineResumeState);
    }

    public void cancelPipeline(String schemaId) {
        AtomicBoolean flag = cancelFlags.get(schemaId);
        if (flag != null) flag.set(true);
        CompletableFuture<?> future = runningPipelines.get(schemaId);
        if (future != null) future.cancel(true);
    }

    /** Returns the stage results for a schema, or empty map if none. */
    public Map<String, String> getStageResults(String schemaId) {
        ConcurrentHashMap<String, String> results = stageResults.get(schemaId);
        return results != null ? Collections.unmodifiableMap(results) : Collections.emptyMap();
    }

    public boolean isPipelineRunning(String schemaId) {
        CompletableFuture<?> future = runningPipelines.get(schemaId);
        return future != null && !future.isDone();
    }

    public void clearStaleApprovals(String schemaId, Map<String, Map<String, String>> stateNodeResults) {
        if (stateNodeResults == null) return;
        Map<String, String> nodeResults = stateNodeResults.get(schemaId);
        if (nodeResults == null) return;
        nodeResults.keySet().removeIf(k -> k.endsWith(":approved"));
    }

    // ── dedicated mutation methods ──

    /** Register a new pipeline run with cancel flag and stage results map. */
    public void registerPipeline(String schemaId, CompletableFuture<?> future, AtomicBoolean cancelFlag) {
        runningPipelines.put(schemaId, future);
        cancelFlags.put(schemaId, cancelFlag);
        stageResults.put(schemaId, new ConcurrentHashMap<>());
    }

    /** Register only cancel flag + stage results (for resume flows). */
    public void registerCancelAndResults(String schemaId, AtomicBoolean cancelFlag) {
        cancelFlags.put(schemaId, cancelFlag);
        stageResults.put(schemaId, new ConcurrentHashMap<>());
    }

    /** Unregister all state for a pipeline. */
    public void unregisterPipeline(String schemaId) {
        runningPipelines.remove(schemaId);
        cancelFlags.remove(schemaId);
        stageResults.remove(schemaId);
    }

    /** Store a single stage result, creating the inner map if absent. */
    public void putStageResult(String schemaId, String stageId, String output) {
        stageResults.computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                .put(stageId, output);
        // Evict oldest schemas if map exceeds max entries
        if (stageResults.size() > MAX_STAGE_RESULT_SCHEMAS) {
            String eldest = stageResults.keySet().stream().findFirst().orElse(null);
            if (eldest != null) stageResults.remove(eldest);
            log.warn("stageResults exceeded {} schemas, evicted oldest entry", MAX_STAGE_RESULT_SCHEMAS);
        }
    }

    /** Remove a completed future from running pipelines (without cancelling). */
    public void removeFuture(String schemaId) {
        runningPipelines.remove(schemaId);
        stageResults.remove(schemaId);
    }

    /** Remove the cancel flag for a schema (keeps stage results intact for post-run inspection). */
    public void removeCancelFlag(String schemaId) {
        cancelFlags.remove(schemaId);
    }

    /** Check if a resume state exists for the schema. */
    public boolean hasResumeState(String schemaId) {
        return pipelineResumeState.containsKey(schemaId);
    }

    /** Store resume state for a schema. */
    public void storeResumeState(String schemaId, int index) {
        pipelineResumeState.put(schemaId, index);
        pipelineResumeTimestamps.put(schemaId, System.currentTimeMillis());
    }

    /** Consume and remove resume state, returning the stored index. */
    public Integer consumeResumeState(String schemaId) {
        return pipelineResumeState.remove(schemaId);
    }

    /** Check if pipeline is currently running for the schema. */
    public CompletableFuture<?> getRunningFuture(String schemaId) {
        return runningPipelines.get(schemaId);
    }

    /**
     * Periodically clean stale pipeline resume state and stage results.
     * Runs every hour and removes entries older than 24 hours.
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupStaleState() {
        long cutoff = System.currentTimeMillis() - 86400000; // 24 hours
        pipelineResumeTimestamps.entrySet().removeIf(e -> e.getValue() < cutoff);
        // Sync pipelineResumeState with cleaned timestamps
        pipelineResumeState.keySet().removeIf(k -> !pipelineResumeTimestamps.containsKey(k));
        // Also clean stageResults — remove entries whose schema has no resume state
        stageResults.entrySet().removeIf(e -> {
            return !pipelineResumeState.containsKey(e.getKey());
        });
    }
}
