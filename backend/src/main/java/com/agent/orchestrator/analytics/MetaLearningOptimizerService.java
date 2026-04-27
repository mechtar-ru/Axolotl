package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.service.SchemaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Deploys meta-learning optimization for workflows by:
 * - Analyzing workflows for optimization opportunities
 * - Using WorkflowMetaLearner to generate optimized variants
 * - Running A/B tests to evaluate variants
 * - Deploying winning variants based on test results
 * 
 * Integration Points:
 * - SchemaService: For loading/saving workflow schemas
 * - WorkflowMetaLearner: For generating workflow variants
 * - WebSocket: For reporting optimization progress
 * - ExecutionWebSocketHandler: For A/B test execution feedback
 */

public class MetaLearningOptimizerService {
    private static final Logger logger = LoggerFactory.getLogger(MetaLearningOptimizerService.class);
    
    private final WorkflowMetaLearner metaLearner;
    private final SchemaService schemaService;
    private final ExecutorService executor;
    
    private static final double MIN_IMPROVEMENT_THRESHOLD = 0.1;
    private static final double CONFIDENCE_THRESHOLD = 0.95;
    
    private final Map<String, ABTestResult> abTestResults = new ConcurrentHashMap<>();
    private final Map<String, WorkflowSchema> variantSchemas = new ConcurrentHashMap<>();
    private Consumer<String> progressCallback;
    
    public MetaLearningOptimizerService(WorkflowMetaLearner metaLearner, SchemaService schemaService) {
        this.metaLearner = metaLearner;
        this.schemaService = schemaService;
        this.executor = Executors.newSingleThreadExecutor();
    }
    
    public void setProgressCallback(Consumer<String> callback) {
        this.progressCallback = callback;
    }
    
    public void optimizeWorkflow(String workflowId) {
        logger.info("Starting optimization for workflow: {}", workflowId);
        reportProgress("Loading workflow: " + workflowId);
        
        WorkflowSchema original = schemaService.getSchema(workflowId);
        if (original == null) {
            logger.error("Workflow not found: {}", workflowId);
            return;
        }
        
        reportProgress("Generating variants...");
        List<WorkflowSchema> variants = generateVariants(original);
        
        reportProgress("Running A/B tests for " + variants.size() + " variants...");
        List<ABTestResult> results = runABTests(original, variants);
        
        reportProgress("Evaluating results...");
        Optional<ABTestResult> winner = findWinner(results);
        
        if (winner.isPresent() && winner.get().isStatisticallySignificant(CONFIDENCE_THRESHOLD)) {
            ABTestResult w = winner.get();
            if (w.getImprovement() >= MIN_IMPROVEMENT_THRESHOLD) {
                reportProgress("Deploying improved variant: " + w.getVariantId());
                deployVariant(w.getVariantId());
            } else {
                reportProgress("No significant improvement found (improvement: " + w.getImprovement() + ")");
            }
        } else {
            reportProgress("No statistically significant winner found");
        }
        
        logger.info("Optimization complete for workflow: {}", workflowId);
    }
    
    public CompletableFuture<Void> optimizeWorkflowAsync(String workflowId) {
        return CompletableFuture.runAsync(() -> optimizeWorkflow(workflowId), executor);
    }
    
    public List<WorkflowSchema> generateVariants(WorkflowSchema original) {
        Map<String, NodePerformanceMetrics> history = loadExecutionHistory(original.getId());
        metaLearner.setExecutionHistory(history);
        return metaLearner.generateMutations(original);
    }
    
    public List<ABTestResult> runABTests(WorkflowSchema control, List<WorkflowSchema> variants) {
        List<ABTestResult> results = new ArrayList<>();
        
        for (WorkflowSchema variant : variants) {
            ABTestResult result = runSingleTest(control, variant);
            results.add(result);
            abTestResults.put(variant.getId(), result);
            variantSchemas.put(variant.getId(), variant);
        }
        
        return results;
    }
    
    private ABTestResult runSingleTest(WorkflowSchema control, WorkflowSchema variant) {
        double controlScore = evaluateWorkflow(control);
        double variantScore = evaluateWorkflow(variant);
        
        int controlRuns = 10;
        int variantRuns = 10;
        
        double controlAvg = simulateMultipleRuns(control, controlRuns);
        double variantAvg = simulateMultipleRuns(variant, variantRuns);
        
        double improvement = (variantAvg - controlAvg) / controlAvg;
        boolean significant = Math.abs(improvement) > 0.05;
        
        return new ABTestResult(
            control.getId(),
            variant.getId(),
            controlAvg,
            variantAvg,
            improvement,
            significant,
            controlRuns + variantRuns
        );
    }
    
    private double evaluateWorkflow(WorkflowSchema workflow) {
        return metaLearner.getFitnessFunction() != null 
            ? metaLearner.getFitnessFunction().evaluate(workflow) 
            : -workflow.getNodes().size();
    }
    
    private double simulateMultipleRuns(WorkflowSchema workflow, int runs) {
        double total = 0;
        for (int i = 0; i < runs; i++) {
            total += evaluateWorkflow(workflow);
        }
        return total / runs;
    }
    
    private Optional<ABTestResult> findWinner(List<ABTestResult> results) {
        return results.stream()
            .filter(ABTestResult::isSignificant)
            .max(Comparator.comparingDouble(ABTestResult::getImprovement));
    }
    
    public void deployVariant(String variantId) {
        logger.info("Deploying variant: {}", variantId);
        WorkflowSchema variant = variantSchemas.get(variantId);
        if (variant != null) {
            schemaService.updateSchema(variant.getId(), variant);
        }
    }
    
    private Map<String, NodePerformanceMetrics> loadExecutionHistory(String workflowId) {
        return new HashMap<>();
    }
    
    private void reportProgress(String message) {
        logger.info(message);
        if (progressCallback != null) {
            progressCallback.accept(message);
        }
    }
    
    public ABTestResult getTestResult(String variantId) {
        return abTestResults.get(variantId);
    }
    
    public Map<String, ABTestResult> getAllTestResults() {
        return new HashMap<>(abTestResults);
    }
    
    @FunctionalInterface
    public interface FitnessFunction {
        double evaluate(WorkflowSchema workflow);
    }
    
    public void setFitnessFunction(FitnessFunction function) {
        metaLearner.setFitnessFunction(workflow -> function.evaluate(workflow));
    }
    
    public WorkflowMetaLearner getFitnessFunction() {
        return metaLearner;
    }
    
    public static class ABTestResult {
        private final String controlId;
        private final String variantId;
        private final double controlScore;
        private final double variantScore;
        private final double improvement;
        private final boolean significant;
        private final int sampleSize;
        
        public ABTestResult(String controlId, String variantId, double controlScore, 
                          double variantScore, double improvement, boolean significant, int sampleSize) {
            this.controlId = controlId;
            this.variantId = variantId;
            this.controlScore = controlScore;
            this.variantScore = variantScore;
            this.improvement = improvement;
            this.significant = significant;
            this.sampleSize = sampleSize;
        }
        
        public String getControlId() { return controlId; }
        public String getVariantId() { return variantId; }
        public double getControlScore() { return controlScore; }
        public double getVariantScore() { return variantScore; }
        public double getImprovement() { return improvement; }
        public boolean isSignificant() { return significant; }
        public int getSampleSize() { return sampleSize; }
        
        public boolean isStatisticallySignificant(double confidenceLevel) {
            double zScore = 1.96;
            double p1 = (controlScore + 1) / (sampleSize + 2);
            double p2 = (variantScore + 1) / (sampleSize + 2);
            double pooledP = (controlScore + variantScore + 2) / (2 * sampleSize + 4);
            double se = Math.sqrt(pooledP * (1 - pooledP) * (2.0 / sampleSize));
            return se > 0 && Math.abs(p1 - p2) / se > zScore;
        }
        
        @Override
        public String toString() {
            return "ABTestResult{control=" + controlId + ", variant=" + variantId + 
                   ", improvement=" + improvement + ", significant=" + significant + "}";
        }
    }
}