package com.agent.orchestrator.repository;

import com.agent.orchestrator.graph.model.GraphPlanStep;
import com.agent.orchestrator.graph.repository.Neo4jPlanStepRepository;
import com.agent.orchestrator.model.PlanStep;
import com.agent.orchestrator.model.PlanStepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class PlanStepRepository {

    private static final Logger log = LoggerFactory.getLogger(PlanStepRepository.class);

    private final Neo4jPlanStepRepository neo4jRepo;

    public PlanStepRepository(Neo4jPlanStepRepository neo4jRepo) {
        this.neo4jRepo = neo4jRepo;
    }

    // ─── CRUD ───

    public PlanStep save(PlanStep step) {
        try {
            GraphPlanStep saved = neo4jRepo.save(toGraph(step));
            return toPoco(saved);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save plan step: " + e.getMessage(), e);
        }
    }

    public PlanStep findById(String id) {
        try {
            return neo4jRepo.findById(id).map(this::toPoco).orElse(null);
        } catch (Exception e) {
            log.error("Error reading plan step {}: {}", id, e.getMessage(), e);
            return null;
        }
    }

    public List<PlanStep> findBySchemaId(String schemaId) {
        try {
            return neo4jRepo.findBySchemaIdOrderByStepIdAsc(schemaId).stream()
                    .map(this::toPoco)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error reading plan steps for schema {}: {}", schemaId, e.getMessage(), e);
            return List.of();
        }
    }

    public List<PlanStep> findBySchemaIdAndStatus(String schemaId, String status) {
        try {
            return neo4jRepo.findBySchemaIdAndStatus(schemaId, status).stream()
                    .map(this::toPoco)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error reading plan steps for schema {} status {}: {}", schemaId, status, e.getMessage(), e);
            return List.of();
        }
    }

    public List<PlanStep> findReadySteps(String schemaId) {
        try {
            return neo4jRepo.findReadySteps(schemaId).stream()
                    .map(this::toPoco)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error reading ready steps for schema {}: {}", schemaId, e.getMessage(), e);
            return List.of();
        }
    }

    public void delete(String id) {
        try {
            neo4jRepo.deleteById(id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete plan step: " + e.getMessage(), e);
        }
    }

    public void deleteAllBySchemaId(String schemaId) {
        try {
            neo4jRepo.deleteAllBySchemaId(schemaId);
        } catch (Exception e) {
            log.error("Error deleting plan steps for schema {}: {}", schemaId, e.getMessage(), e);
        }
    }

    public void updateStatus(String schemaId, String stepId, String status) {
        try {
            neo4jRepo.updateStatus(schemaId, stepId, status);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update plan step status: " + e.getMessage(), e);
        }
    }

    // ─── Conversion ───

    private GraphPlanStep toGraph(PlanStep p) {
        GraphPlanStep g = new GraphPlanStep();
        g.setId(p.getId());
        g.setStepId(p.getStepId());
        g.setTitle(p.getTitle());
        g.setDescription(p.getDescription());
        g.setStatus(p.getStatus() != null ? p.getStatus().name() : "PENDING");
        g.setSchemaId(p.getSchemaId());
        g.setPlanId(p.getPlanId());
        g.setReason(p.getReason());
        g.setCreatedAt(p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        g.setUpdatedAt(p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null);

        // Resolve dependency relationships
        if (p.getDependsOn() != null && !p.getDependsOn().isEmpty()) {
            List<GraphPlanStep> deps = new ArrayList<>();
            for (String depId : p.getDependsOn()) {
                neo4jRepo.findById(depId).ifPresent(deps::add);
            }
            g.setDependsOn(deps);
        }

        return g;
    }

    private PlanStep toPoco(GraphPlanStep g) {
        PlanStep p = new PlanStep();
        p.setId(g.getId());
        p.setStepId(g.getStepId());
        p.setTitle(g.getTitle());
        p.setDescription(g.getDescription());
        if (g.getStatus() != null) {
            try {
                p.setStatus(PlanStepStatus.valueOf(g.getStatus()));
            } catch (IllegalArgumentException e) {
                p.setStatus(PlanStepStatus.PENDING);
            }
        }
        p.setSchemaId(g.getSchemaId());
        p.setPlanId(g.getPlanId());
        p.setReason(g.getReason());
        if (g.getCreatedAt() != null) p.setCreatedAt(Instant.parse(g.getCreatedAt()));
        if (g.getUpdatedAt() != null) p.setUpdatedAt(Instant.parse(g.getUpdatedAt()));

        // Extract dependency IDs from relationships
        if (g.getDependsOn() != null) {
            p.setDependsOn(g.getDependsOn().stream()
                    .map(GraphPlanStep::getId)
                    .collect(Collectors.toList()));
        }

        return p;
    }
}
