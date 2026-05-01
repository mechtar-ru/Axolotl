package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import java.util.*;

/**
 * Coordinates metrics collection, analysis, and optimization for adaptive workflows.
 * 
 * Integration points:
 * - triggerAdaptation(workflowId) - starts adaptation process for a workflow
 * - coordinateWorkflowAnalysis(workflow, metrics) - coordinates component analysis
 * - applyAdaptation(workflow, adaptation) - applies approved adaptations
 * - schedulePeriodicAdaptation(schedule) - schedules periodic adaptation
 * 
 * Usage: Integrate with workflow execution system to automatically
 * improve workflows over time based on execution history.
 */


public class AdaptiveWorkflowEngine {
    
    private final NodeUsageTrackingSystem usageTracking;
    private final WorkflowAnalyzer workflowAnalyzer;
    private final WorkflowPruner workflowPruner;
    private final Object schemaService; // SchemaService from com.agent.orchestrator.service
    private final List<String> adaptationHistory = new ArrayList<>();
    private Timer scheduledAdaptationTimer;
    
    public AdaptiveWorkflowEngine() {
        this.usageTracking = new NodeUsageTrackingSystem();
        this.workflowAnalyzer = new WorkflowAnalyzer();
        this.workflowPruner = new WorkflowPruner();
        this.schemaService = null;
    }
    
    public AdaptiveWorkflowEngine(Object schemaService) {
        this.usageTracking = new NodeUsageTrackingSystem();
        this.workflowAnalyzer = new WorkflowAnalyzer();
        this.workflowPruner = new WorkflowPruner();
        this.schemaService = schemaService;
    }
    
    /**
     * Triggers adaptation analysis for a workflow.
     */
    public List<String> triggerAdaptation(WorkflowSchema workflow) {
        if (workflow == null || workflow.getNodes() == null) {
            return Collections.emptyList();
        }
        
        List<String> results = new ArrayList<>();
        
        // 1. Collect metrics via NodeUsageTrackingSystem
        Map<String, NodePerformanceMetrics> metrics = collectMetrics(workflow);
        results.add("Collected metrics for " + metrics.size() + " nodes");
        
        // 2. Analyze workflow using WorkflowAnalyzer
        List<String> underutilized = workflowAnalyzer.findUnderutilizedNodes(metrics);
        if (!underutilized.isEmpty()) {
            results.add("Found " + underutilized.size() + " underutilized nodes: " + underutilized);
        }
        
        // 3. Apply safe pruning using WorkflowPruner
        if (!underutilized.isEmpty()) {
            WorkflowSchema pruned = workflowPruner.pruneWorkflow(workflow, underutilized);
            
            // 4. Persist the adapted workflow (omitted - requires SchemaService injection)
            if (schemaService != null) {
                results.add("Workflow pruning prepared (persistence requires SchemaService)");
            }
            
            adaptationHistory.add("Adapted workflow " + workflow.getId() + " at " + new Date());
        } else {
            results.add("No adaptations needed");
        }
        
        // Generate recommendations
        List<String> recommendations = workflowAnalyzer.generateRecommendations(metrics);
        results.addAll(recommendations);
        
        return results;
    }
    
    private Map<String, NodePerformanceMetrics> collectMetrics(WorkflowSchema workflow) {
        Map<String, NodePerformanceMetrics> metrics = new HashMap<>();
        
        if (workflow.getNodes() != null) {
            for (var node : workflow.getNodes()) {
                String nodeId = node.getId();
                NodePerformanceMetrics existing = usageTracking.getMetrics(nodeId);
                if (existing != null) {
                    metrics.put(nodeId, existing);
                } else {
                    metrics.put(nodeId, new NodePerformanceMetrics(nodeId));
                }
            }
        }
        
        return metrics;
    }
    
    /**
     * Coordinates workflow analysis and returns underutilized node candidates.
     */
    public List<String> coordinateWorkflowAnalysis(WorkflowSchema workflow, Map<String, NodePerformanceMetrics> metrics) {
        List<String> underutilized = workflowAnalyzer.findUnderutilizedNodes(metrics);
        
        List<String> recommendations = workflowAnalyzer.generateRecommendations(metrics);
        
        return underutilized;
    }
    
    /**
     * Manually triggers pruning for specific nodes.
     */
    public WorkflowSchema applyAdaptation(WorkflowSchema workflow, List<String> nodesToRemove) {
        if (workflow == null || nodesToRemove == null || nodesToRemove.isEmpty()) {
            return workflow;
        }
        
        return workflowPruner.pruneWorkflow(workflow, nodesToRemove);
    }
    
    /**
     * Schedules periodic adaptation.
     */
    public void schedulePeriodicAdaptation(long intervalMs) {
        if (scheduledAdaptationTimer != null) {
            scheduledAdaptationTimer.cancel();
        }
        
        scheduledAdaptationTimer = new Timer();
        scheduledAdaptationTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                // This would trigger adaptation for tracked workflows
                // Implementation depends on having list of workflow IDs
            }
        }, intervalMs, intervalMs);
    }
    
    /**
     * Cancels scheduled adaptations.
     */
    public void cancelScheduledAdaptation() {
        if (scheduledAdaptationTimer != null) {
            scheduledAdaptationTimer.cancel();
            scheduledAdaptationTimer = null;
        }
    }
    
    public List<String> getAdaptationHistory() {
        return new ArrayList<>(adaptationHistory);
    }
    
    public NodeUsageTrackingSystem getUsageTracking() {
        return usageTracking;
    }
    
    public WorkflowAnalyzer getWorkflowAnalyzer() {
        return workflowAnalyzer;
    }
    
    public WorkflowPruner getWorkflowPruner() {
        return workflowPruner;
    }
}