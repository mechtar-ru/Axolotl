package com.agent.orchestrator.mcp;

import com.agent.orchestrator.model.PlanStep;
import com.agent.orchestrator.model.PlanStepStatus;
import com.agent.orchestrator.service.PlanStepService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for PlanStep management (the new graph-based plan system).
 * Provides: read_plan_steps, add_plan_step, update_plan_step_status, get_ready_steps, get_plan_graph
 */
public class PlanStepTools {

    private final PlanStepService planStepService;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanStepTools(PlanStepService planStepService) {
        this.planStepService = planStepService;
    }

    public List<PlanTools.ToolSpec> getToolSpecs() {
        return List.of(
                new PlanTools.ToolSpec(
                        "read_plan_steps",
                        "Read all plan steps for a schema/project. Returns steps with statuses, dependencies, and dependency graph.",
                        Map.of(
                                "schema_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "Schema ID to read plan steps for"
                                ),
                                "format", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "enum", List.of("full", "steps_only", "ready_only"),
                                        "description", "Output format (default: full)"
                                )
                        )
                ),
                new PlanTools.ToolSpec(
                        "add_plan_steps",
                        "Add multiple plan steps to a schema's plan. Each step must have a title and can have description, depends_on (list of step stepIds).",
                        Map.of(
                                "schema_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "Schema ID to add steps to"
                                ),
                                "steps", Map.of(
                                        "type", "array",
                                        "required", true,
                                        "description", "Array of step objects: {title: string, description?: string, depends_on?: [int]}"
                                )
                        )
                ),
                new PlanTools.ToolSpec(
                        "update_plan_step_status",
                        "Change status of a plan step. Validates dependencies when marking DONE.",
                        Map.of(
                                "schema_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "Schema ID"
                                ),
                                "step_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "ID of the step to update"
                                ),
                                "status", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "enum", List.of("PENDING", "IN_PROGRESS", "DONE", "REJECTED", "INCOMPLETE"),
                                        "description", "New status"
                                ),
                                "reason", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Optional reason for status change"
                                )
                        )
                ),
                new PlanTools.ToolSpec(
                        "get_ready_steps",
                        "Get plan steps whose dependencies are all DONE, ready for implementation.",
                        Map.of(
                                "schema_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "Schema ID"
                                )
                        )
                ),
                new PlanTools.ToolSpec(
                        "get_plan_graph",
                        "Get the dependency graph of all plan steps (nodes + edges).",
                        Map.of(
                                "schema_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "Schema ID"
                                )
                        )
                )
        );
    }

    public String callTool(String toolName, Map<String, Object> args) {
        if (args == null) args = Map.of();
        return switch (toolName) {
            case "read_plan_steps" -> readPlanSteps(args);
            case "add_plan_steps" -> addPlanSteps(args);
            case "update_plan_step_status" -> updatePlanStepStatus(args);
            case "get_ready_steps" -> getReadySteps(args);
            case "get_plan_graph" -> getPlanGraph(args);
            default -> "ERROR: Unknown tool: " + toolName;
        };
    }

    private String readPlanSteps(Map<String, Object> args) {
        String schemaId = requireArg(args, "schema_id");
        String format = (String) args.getOrDefault("format", "full");

        List<PlanStep> steps = planStepService.getSteps(schemaId);

        if ("ready_only".equals(format)) {
            List<PlanStep> ready = planStepService.getReadySteps(schemaId);
            return formatStepsList(ready);
        }

        if ("steps_only".equals(format)) {
            return formatStepsList(steps);
        }

        // full format
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaId", schemaId);
            result.put("totalSteps", steps.size());
            result.put("byStatus", steps.stream()
                    .collect(Collectors.groupingBy(s -> s.getStatus().name(), Collectors.counting())));
            result.put("steps", steps.stream().map(this::stepToMap).collect(Collectors.toList()));
            result.put("graph", planStepService.getDependencyGraph(schemaId));
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String addPlanSteps(Map<String, Object> args) {
        String schemaId = requireArg(args, "schema_id");
        Object stepsObj = args.get("steps");
        if (!(stepsObj instanceof List<?> stepList) || stepList.isEmpty()) {
            return "ERROR: 'steps' must be a non-empty array";
        }

        List<PlanStep> steps = new ArrayList<>();
        for (Object item : stepList) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String title = (String) map.get("title");
            if (title == null || title.isBlank()) continue;

            PlanStep step = new PlanStep();
            step.setTitle(title);
            step.setDescription((String) map.get("description"));

            Object depsObj = map.get("depends_on");
            if (depsObj instanceof List<?> deps) {
                // depends_on references step IDs (strings)
                List<String> depIds = new ArrayList<>();
                for (Object d : deps) {
                    depIds.add(d.toString());
                }
                step.setDependsOn(depIds);
            }
            steps.add(step);
        }

        if (steps.isEmpty()) {
            return "ERROR: No valid steps in array. Each must have a non-empty 'title'.";
        }

        try {
            List<PlanStep> created = planStepService.createSteps(schemaId, steps);
            return "OK: " + created.size() + " steps added — " +
                    created.stream().map(PlanStep::getTitle).collect(Collectors.joining(", "));
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String updatePlanStepStatus(Map<String, Object> args) {
        String schemaId = requireArg(args, "schema_id");
        String stepId = requireArg(args, "step_id");
        String statusStr = requireArg(args, "status");
        String reason = (String) args.get("reason");

        PlanStepStatus status;
        try {
            status = PlanStepStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            return "ERROR: Invalid status: " + statusStr + ". Must be one of: PENDING, IN_PROGRESS, DONE, REJECTED, INCOMPLETE";
        }

        try {
            PlanStep step = planStepService.updateStatus(schemaId, stepId, status, reason);
            return "OK: Step '" + step.getTitle() + "' status changed to " + status + " (stepId: " + step.getStepId() + ")";
        } catch (IllegalStateException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String getReadySteps(Map<String, Object> args) {
        String schemaId = requireArg(args, "schema_id");
        List<PlanStep> ready = planStepService.getReadySteps(schemaId);
        return formatStepsList(ready);
    }

    private String getPlanGraph(Map<String, Object> args) {
        String schemaId = requireArg(args, "schema_id");
        try {
            Map<String, Object> graph = planStepService.getDependencyGraph(schemaId);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(graph);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ─── Helpers ───

    private String formatStepsList(List<PlanStep> steps) {
        if (steps.isEmpty()) return "No plan steps found.";
        StringBuilder sb = new StringBuilder();
        for (PlanStep s : steps) {
            String statusIcon = switch (s.getStatus()) {
                case PENDING -> "⬜";
                case IN_PROGRESS -> "🔄";
                case DONE -> "✅";
                case REJECTED -> "❌";
                case INCOMPLETE -> "⚠️";
            };
            sb.append(String.format("%s [%s] #%d %s (id: %s)",
                    statusIcon, s.getStatus(), s.getStepId(), s.getTitle(), s.getId()));
            if (s.getDependsOn() != null && !s.getDependsOn().isEmpty()) {
                sb.append(" depends on: ").append(String.join(", ", s.getDependsOn()));
            }
            if (s.getReason() != null) {
                sb.append(" reason: ").append(s.getReason());
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private Map<String, Object> stepToMap(PlanStep s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("stepId", s.getStepId());
        m.put("title", s.getTitle());
        m.put("description", s.getDescription());
        m.put("status", s.getStatus().name());
        m.put("dependsOn", s.getDependsOn());
        m.put("reason", s.getReason());
        return m;
    }

    private String requireArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return value.toString();
    }
}
