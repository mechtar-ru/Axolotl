package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.model.PlanStep;
import com.agent.orchestrator.model.PlanStepStatus;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.PlanStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class PlanStepService {

    private static final Logger log = LoggerFactory.getLogger(PlanStepService.class);

    private final PlanStepRepository repository;
    private final Neo4jSchemaRepository schemaRepository;

    public PlanStepService(PlanStepRepository repository, Neo4jSchemaRepository schemaRepository) {
        this.repository = repository;
        this.schemaRepository = schemaRepository;
    }

    public List<PlanStep> getSteps(String schemaId) {
        return repository.findBySchemaId(schemaId);
    }

    public PlanStep getStep(String schemaId, String stepId) {
        return repository.findById(stepId);
    }

    public List<PlanStep> createSteps(String schemaId, List<PlanStep> steps) {
        List<PlanStep> created = new ArrayList<>();
        int maxStepId = repository.findBySchemaId(schemaId).stream()
                .mapToInt(PlanStep::getStepId)
                .max().orElse(0);

        for (int i = 0; i < steps.size(); i++) {
            PlanStep s = steps.get(i);
            s.setSchemaId(schemaId);
            if (s.getStepId() <= 0) {
                s.setStepId(maxStepId + i + 1);
            }
            if (s.getStatus() == null) {
                s.setStatus(PlanStepStatus.PENDING);
            }
            created.add(repository.save(s));
        }
        return created;
    }

    public PlanStep updateStatus(String schemaId, String stepId, PlanStepStatus status, String reason) {
        // Validate dependencies when transitioning to DONE
        if (status == PlanStepStatus.DONE) {
            PlanStep step = repository.findById(stepId);
            if (step != null && step.getDependsOn() != null && !step.getDependsOn().isEmpty()) {
                for (String depId : step.getDependsOn()) {
                    PlanStep dep = repository.findById(depId);
                    if (dep != null && dep.getStatus() != PlanStepStatus.DONE) {
                        throw new IllegalStateException(
                                "Cannot mark step as DONE — dependency not done: " + dep.getTitle()
                                + " (" + dep.getStatus() + ")");
                    }
                }
            }
        }

        repository.updateStatus(schemaId, stepId, status.name());
        PlanStep updated = repository.findById(stepId);
        if (updated != null && reason != null) {
            updated.setReason(reason);
            repository.save(updated);
        }
        return updated;
    }

    public List<PlanStep> getReadySteps(String schemaId) {
        return repository.findReadySteps(schemaId);
    }

    public Map<String, Object> getDependencyGraph(String schemaId) {
        List<PlanStep> steps = repository.findBySchemaId(schemaId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> readySet = repository.findReadySteps(schemaId).stream()
                .map(PlanStep::getId)
                .collect(Collectors.toSet());

        for (PlanStep s : steps) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", s.getId());
            node.put("stepId", s.getStepId());
            node.put("title", s.getTitle());
            node.put("status", s.getStatus().name());
            node.put("ready", readySet.contains(s.getId()));
            nodes.add(node);

            if (s.getDependsOn() != null) {
                for (String depId : s.getDependsOn()) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", depId);
                    edge.put("to", s.getId());
                    edges.add(edge);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    public void deleteAllBySchemaId(String schemaId) {
        repository.deleteAllBySchemaId(schemaId);
    }

    public void deleteSteps(String schemaId) {
        repository.deleteAllBySchemaId(schemaId);
    }

    /**
     * Sync Neo4j plan steps to disk as markdown files under {targetPath}/plan/.
     * Resolves targetPath from the schema's stored path (not user-supplied) to prevent path traversal.
     * Creates:
     *   - plan/implementation_plan.md — summary overview
     *   - plan/steps/{stepId}-{kebab-title}.md — individual step files
     */
    public String syncToDisk(String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema == null || schema.getTargetPath() == null || schema.getTargetPath().isBlank()) {
            return "ERROR: Schema " + schemaId + " has no targetPath configured";
        }
        String targetPath = schema.getTargetPath();

        List<PlanStep> steps = repository.findBySchemaId(schemaId);
        if (steps == null || steps.isEmpty()) {
            return "No plan steps to sync for schema " + schemaId;
        }

        Path planDir = Path.of(targetPath, "plan");
        Path stepsDir = planDir.resolve("steps");
        try {
            Files.createDirectories(stepsDir);
        } catch (IOException e) {
            return "ERROR: Cannot create plan directory: " + e.getMessage();
        }

        int written = 0;
        StringBuilder summary = new StringBuilder();

        // Write individual step files
        for (PlanStep step : steps) {
            String filename = step.getStepId() + "-" +
                    step.getTitle().toLowerCase()
                            .replaceAll("[^a-z0-9]+", "-")
                            .replaceAll("^-|-$", "") + ".md";
            filename = filename.substring(0, Math.min(filename.length(), 80));

            StringBuilder md = new StringBuilder();
            md.append("---\n");
            md.append("id: ").append(step.getStepId()).append("\n");
            md.append("title: \"").append(escapeYaml(step.getTitle())).append("\"\n");
            md.append("status: ").append(step.getStatus().name()).append("\n");
            if (step.getDependsOn() != null && !step.getDependsOn().isEmpty()) {
                md.append("depends_on:\n");
                for (String depId : step.getDependsOn()) {
                    PlanStep dep = repository.findById(depId);
                    if (dep != null) {
                        md.append("  - ").append(dep.getStepId()).append(" # ").append(dep.getTitle()).append("\n");
                    } else {
                        md.append("  - \"").append(depId).append("\"\n");
                    }
                }
            }
            if (step.getReason() != null && !step.getReason().isBlank()) {
                md.append("reason: \"").append(escapeYaml(step.getReason())).append("\"\n");
            }
            md.append("---\n\n");
            md.append("# ").append(step.getTitle()).append("\n\n");
            if (step.getDescription() != null && !step.getDescription().isBlank()) {
                md.append(step.getDescription()).append("\n");
            }

            try {
                Files.writeString(stepsDir.resolve(filename), md);
                written++;
                summary.append("- ").append(filename).append("\n");
            } catch (IOException e) {
                summary.append("- ERROR writing ").append(filename).append(": ").append(e.getMessage()).append("\n");
            }
        }

        // Write summary implementation_plan.md
        if (!steps.isEmpty()) {
            StringBuilder overview = new StringBuilder();
            overview.append("# Implementation Plan\n\n");
            overview.append("Total steps: ").append(steps.size()).append("\n\n");
            overview.append("## Steps\n\n");
            for (PlanStep s : steps) {
                String icon = switch (s.getStatus()) {
                    case PENDING -> "⬜";
                    case IN_PROGRESS -> "🔄";
                    case DONE -> "✅";
                    case REJECTED -> "❌";
                    case INCOMPLETE -> "⚠️";
                };
                overview.append("### ").append(icon).append(" ").append(s.getTitle()).append("\n");
                overview.append("- **Status:** ").append(s.getStatus()).append("\n");
                overview.append("- **Step ID:** ").append(s.getStepId()).append("\n");
                if (s.getDependsOn() != null && !s.getDependsOn().isEmpty()) {
                    List<String> depTitles = new ArrayList<>();
                    for (String depId : s.getDependsOn()) {
                        PlanStep dep = repository.findById(depId);
                        if (dep != null) {
                            depTitles.add("#" + dep.getStepId() + " " + dep.getTitle());
                        }
                    }
                    overview.append("- **Depends on:** ").append(String.join(", ", depTitles)).append("\n");
                }
                if (s.getDescription() != null && !s.getDescription().isBlank()) {
                    overview.append("\n").append(s.getDescription()).append("\n");
                }
                overview.append("\n");
            }

            try {
                Files.writeString(planDir.resolve("implementation_plan.md"), overview.toString());
            } catch (IOException e) {
                summary.append("- ERROR writing implementation_plan.md: ").append(e.getMessage()).append("\n");
            }
        }

        return "Synced " + written + "/" + steps.size() + " step files to " + stepsDir + "\n" + summary;
    }

    private String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
