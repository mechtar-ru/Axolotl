package com.agent.orchestrator.graph.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class GraphMetricsService {

    private static final Logger log = LoggerFactory.getLogger(GraphMetricsService.class);

    private final Map<String, MetricSnapshot> metrics = new ConcurrentHashMap<>();
    private final AtomicLong totalImports = new AtomicLong(0);
    private final AtomicLong totalImportTime = new AtomicLong(0);

    public record MetricSnapshot(
            String operation,
            long count,
            long totalTimeMs,
            long minTimeMs,
            long maxTimeMs,
            Instant lastRun
    ) {}

    public void recordImport(long durationMs) {
        totalImports.incrementAndGet();
        totalImportTime.addAndGet(durationMs);
        recordMetric("import", durationMs);
    }

    public void recordQuery(String queryType, long durationMs) {
        recordMetric("query_" + queryType, durationMs);
    }

    public void recordCuration(long durationMs) {
        recordMetric("curation", durationMs);
    }

    private void recordMetric(String operation, long durationMs) {
        metrics.compute(operation, (key, existing) -> {
            if (existing == null) {
                return new MetricSnapshot(operation, 1, durationMs, durationMs, durationMs, Instant.now());
            }
            long newCount = existing.count() + 1;
            long newTotal = existing.totalTimeMs() + durationMs;
            return new MetricSnapshot(
                    operation,
                    newCount,
                    newTotal,
                    Math.min(existing.minTimeMs(), durationMs),
                    Math.max(existing.maxTimeMs(), durationMs),
                    Instant.now()
            );
        });
    }

    public Map<String, MetricSnapshot> getMetrics() {
        return Map.copyOf(metrics);
    }

    public Map<String, Object> getSummary() {
        long imports = totalImports.get();
        long importTime = totalImportTime.get();
        double avgImportTime = imports > 0 ? (double) importTime / imports : 0;

        return Map.of(
                "totalImports", imports,
                "avgImportTimeMs", (long) avgImportTime,
                "operations", getMetrics()
        );
    }

    public void logSummary() {
        var summary = getSummary();
        log.info("Graph Metrics: {}", summary);
    }
}