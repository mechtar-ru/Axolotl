package com.agent.orchestrator.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricsService {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final AtomicInteger activeExecutions = new AtomicInteger(0);
    private final AtomicInteger queuedExecutions = new AtomicInteger(0);
    private final AtomicLong totalNodesExecuted = new AtomicLong(0);
    private final AtomicLong totalWorkflowsExecuted = new AtomicLong(0);

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("axolotl_executions_active", activeExecutions, AtomicInteger::get)
                .description("Currently active workflow executions")
                .register(registry);

        Gauge.builder("axolotl_executions_queued", queuedExecutions, AtomicInteger::get)
                .description("Queued workflow executions")
                .register(registry);

        Gauge.builder("axolotl_nodes_executed_total", totalNodesExecuted, AtomicLong::get)
                .description("Total nodes executed")
                .register(registry);

        Gauge.builder("axolotl_workflows_executed_total", totalWorkflowsExecuted, AtomicLong::get)
                .description("Total workflows executed")
                .register(registry);
    }

    public void recordWorkflowStart() {
        activeExecutions.incrementAndGet();
        totalWorkflowsExecuted.incrementAndGet();
        Counter.builder("axolotl_workflows_started")
                .description("Workflows started")
                .register(registry)
                .increment();
    }

    public void recordWorkflowComplete() {
        activeExecutions.decrementAndGet();
        Counter.builder("axolotl_workflows_completed")
                .description("Workflows completed")
                .register(registry)
                .increment();
    }

    public void recordWorkflowFailed() {
        activeExecutions.decrementAndGet();
        Counter.builder("axolotl_workflows_failed")
                .description("Workflows failed")
                .register(registry)
                .increment();
    }

    public void recordNodeExecution(String nodeType, long durationMs) {
        totalNodesExecuted.incrementAndGet();
        String name = "axolotl_nodes_" + nodeType + "_executed";
        Counter.builder(name)
                .description("Nodes executed by type: " + nodeType)
                .register(registry)
                .increment();

        Timer.builder("axolotl_node_duration_" + nodeType)
                .description("Node execution duration by type")
                .register(registry)
                .record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordLlmCall(String provider, long durationMs, boolean success) {
        Timer.builder("axolotl_llm_call_duration")
                .tag("provider", provider)
                .description("LLM call duration")
                .register(registry)
                .record(java.time.Duration.ofMillis(durationMs));

        Counter.builder("axolotl_llm_calls")
                .tag("provider", provider)
                .tag("success", String.valueOf(success))
                .description("LLM calls by provider and status")
                .register(registry)
                .increment();
    }

    public void recordApiCall(String endpoint, int statusCode) {
        Counter.builder("axolotl_api_calls")
                .tag("endpoint", endpoint)
                .tag("status", String.valueOf(statusCode))
                .description("API calls by endpoint and status")
                .register(registry)
                .increment();
    }
}
