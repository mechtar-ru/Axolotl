package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PipelineStatusManager {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusManager.class);

    private final ConcurrentHashMap<String, CompletableFuture<?>> runningPipelines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> stageResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pipelineResumeState = new ConcurrentHashMap<>();

    public ConcurrentHashMap<String, CompletableFuture<?>> getRunningPipelines() {
        return runningPipelines;
    }

    public ConcurrentHashMap<String, AtomicBoolean> getCancelFlags() {
        return cancelFlags;
    }

    public ConcurrentHashMap<String, ConcurrentHashMap<String, String>> getStageResults() {
        return stageResults;
    }

    public ConcurrentHashMap<String, Integer> getPipelineResumeState() {
        return pipelineResumeState;
    }

    public void cancelPipeline(String schemaId) {
        AtomicBoolean flag = cancelFlags.get(schemaId);
        if (flag != null) flag.set(true);
        CompletableFuture<?> future = runningPipelines.get(schemaId);
        if (future != null) future.cancel(true);
    }

    public Map<String, String> getStageResults(String schemaId) {
        return stageResults.getOrDefault(schemaId, new ConcurrentHashMap<>());
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
}
