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

/**
 * Manages pipeline execution state including running futures, cancel flags, stage results,
 * and resume state. Includes TTL-based eviction to prevent unbounded memory growth.
 */
@Service
public class PipelineStatusManager {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusManager.class);

    /** Max schemas with stored stage results before eviction. */
    private static final int MAX_STAGE_RESULT_SCHEMAS = 50;
    /** TTL for stage results: 24 hours after last update. */
    private static final long STAGE_RESULT_TTL_MS = 86400000;

    private final ConcurrentHashMap<String, CompletableFuture<?>> runningPipelines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, TimedEntry>> stageResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pipelineResumeState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> pipelineResumeTimestamps = new ConcurrentHashMap<>();

    /** Entry with timestamp for TTL-based eviction. */
    private static class TimedEntry {
        final String value;
        final long updatedAt;

        TimedEntry(String value) {
            this.value = value;
            this.updatedAt = System.currentTimeMillis();
        }
    }

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
        // Return a snapshot without timestamps
        Map<String, Map<String, String>> snapshot = new ConcurrentHashMap<>();
        stageResults.forEach((schemaId, inner) -> {
            Map<String, String> copy = new ConcurrentHashMap<>();
            inner.forEach((stageId, entry) -> copy.put(stageId, entry.value));
            snapshot.put(schemaId, copy);
        });
        return Collections.unmodifiableMap(snapshot);
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
        ConcurrentHashMap<String, TimedEntry> results = stageResults.get(schemaId);
        if (results == null) return Collections.emptyMap();
        Map<String, String> copy = new ConcurrentHashMap<>();
        results.forEach((stageId, entry) -> copy.put(stageId, entry.value));
        return Collections.unmodifiableMap(copy);
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
                .put(stageId, new TimedEntry(output));
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
        pipelineResumeTimestamps.remove(schemaId);
        return pipelineResumeState.remove(schemaId);
    }

    /** Check if pipeline is currently running for the schema. */
    public CompletableFuture<?> getRunningFuture(String schemaId) {
        return runningPipelines.get(schemaId);
    }

    /**
     * Periodically clean stale pipeline resume state and stage results.
     * Runs every hour and removes entries older than TTL.
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupStaleState() {
        long cutoff = System.currentTimeMillis() - STAGE_RESULT_TTL_MS;
        
        // Clean resume timestamps
        pipelineResumeTimestamps.entrySet().removeIf(e -> e.getValue() < cutoff);
        pipelineResumeState.keySet().removeIf(k -> !pipelineResumeTimestamps.containsKey(k));

        // Clean stage results by TTL
        long now = System.currentTimeMillis();
        stageResults.forEach((schemaId, inner) -> {
            inner.entrySet().removeIf(e -> (now - e.getValue().updatedAt) > STAGE_RESULT_TTL_MS);
            if (inner.isEmpty()) stageResults.remove(schemaId);
        });

        log.debug("Pipeline status cleanup complete: {} schemas with stage results", stageResults.size());
    }
}
