package com.agent.orchestrator.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricsService {

    private final Counter schemaExecutionsTotal;
    private final Counter schemaExecutionsSuccess;
    private final Counter schemaExecutionsFailed;
    private final Counter nodeExecutionsTotal;
    private final Counter llmCallsTotal;
    private final Counter toolCallsTotal;
    
    private final AtomicInteger activeSchemas = new AtomicInteger(0);
    private final AtomicLong totalExecutionTimeMs = new AtomicLong(0);
    private final AtomicInteger schemasCompleted = new AtomicInteger(0);

    private final Timer schemaExecutionTimer;
    private final Timer nodeExecutionTimer;
    private final Counter tokenEstimatedTotal;
    private final DistributionSummary tokenEstimatedPerCall;

    public MetricsService(MeterRegistry registry) {
        // Schema execution counters
        this.schemaExecutionsTotal = Counter.builder("axolotl.schema.executions.total")
                .description("Total schema executions")
                .register(registry);
        
        this.schemaExecutionsSuccess = Counter.builder("axolotl.schema.executions.success")
                .description("Successful schema executions")
                .register(registry);
        
        this.schemaExecutionsFailed = Counter.builder("axolotl.schema.executions.failed")
                .description("Failed schema executions")
                .register(registry);
        
        this.nodeExecutionsTotal = Counter.builder("axolotl.node.executions.total")
                .description("Total node executions")
                .register(registry);
        
        this.llmCallsTotal = Counter.builder("axolotl.llm.calls.total")
                .description("Total LLM API calls")
                .register(registry);
        
        this.toolCallsTotal = Counter.builder("axolotl.tool.calls.total")
                .description("Total tool calls")
                .register(registry);

        // Gauges
        Gauge.builder("axolotl.schemas.active", activeSchemas, AtomicInteger::get)
                .description("Active schema executions")
                .register(registry);
        
        Gauge.builder("axolotl.schemas.completed", schemasCompleted, AtomicInteger::get)
                .description("Total schemas completed")
                .register(registry);
        
        Gauge.builder("axolotl.execution.time.total_ms", totalExecutionTimeMs, AtomicLong::get)
                .description("Total execution time in milliseconds")
                .register(registry);

        // Timers
        this.schemaExecutionTimer = Timer.builder("axolotl.schema.execution.duration")
                .description("Schema execution duration")
                .register(registry);
        
        this.nodeExecutionTimer = Timer.builder("axolotl.node.execution.duration")
                .description("Node execution duration")
                .register(registry);

        // Token estimation metrics
        this.tokenEstimatedTotal = Counter.builder("axolotl.token.estimated.total")
                .description("Cumulative tokens estimated across all curation calls")
                .register(registry);
        
        this.tokenEstimatedPerCall = DistributionSummary.builder("axolotl.token.estimated.per_call")
                .description("Distribution of token counts per curation call")
                .register(registry);
    }

    public void recordSchemaExecutionStart() {
        schemaExecutionsTotal.increment();
        activeSchemas.incrementAndGet();
    }

    public void recordSchemaExecutionSuccess(long durationMs) {
        schemaExecutionsSuccess.increment();
        activeSchemas.decrementAndGet();
        schemasCompleted.incrementAndGet();
        totalExecutionTimeMs.addAndGet(durationMs);
        schemaExecutionTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordSchemaExecutionFailed(long durationMs) {
        schemaExecutionsFailed.increment();
        activeSchemas.decrementAndGet();
        totalExecutionTimeMs.addAndGet(durationMs);
        schemaExecutionTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordNodeExecution() {
        nodeExecutionsTotal.increment();
    }

    public void recordNodeExecutionTime(long durationMs) {
        nodeExecutionTimer.record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void recordLlmCall() {
        llmCallsTotal.increment();
    }

    public void recordToolCall() {
        toolCallsTotal.increment();
    }

    public void recordTokenCount(int tokenCount) {
        tokenEstimatedTotal.increment(tokenCount);
        tokenEstimatedPerCall.record(tokenCount);
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(schemaExecutionTimer);
    }
}