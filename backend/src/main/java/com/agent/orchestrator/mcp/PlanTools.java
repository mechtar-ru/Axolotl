package com.agent.orchestrator.mcp;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.service.PlanService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Implementation of MCP tools for project plan management.
 * Provides 7 tools: read_plan, add_tasks, update_task_status, move_task, add_task, delete_task, update_task_priority
 */
public class PlanTools {

    private final PlanService planService;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlanTools(PlanService planService) {
        this.planService = planService;
    }

    // === Tool specifications ===

    public List<ToolSpec> getToolSpecs() {
        return List.of(
                new ToolSpec(
                        "read_plan",
                        "Read current plan structure. Returns full plan as JSON with tasks, statuses, order, priorities, dependencies.",
                        Map.of(
                                "workspace_id", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "ID of workspace (defaults to 'default')"
                                ),
                                "format", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "enum", List.of("full", "tasks_only", "status_summary"),
                                        "description", "Output format. 'full' returns everything, 'tasks_only' returns just tasks list, 'status_summary' returns counts by status"
                                ),
                                "status_filter", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "enum", List.of("TODO", "IN_PROGRESS", "DONE", "BLOCKED"),
                                        "description", "Filter tasks by status. Returns only tasks with this status."
                                )
                        )
                ),
                new ToolSpec(
                        "add_tasks",
                        "Add multiple tasks to plan in one call. Each task is an object with 'title' (required), 'priority' (optional), 'description' (optional).",
                        Map.of(
                                "tasks", Map.of(
                                        "type", "array",
                                        "required", true,
                                        "description", "Array of task objects. Each: {title: string, priority?: HIGH|MEDIUM|LOW, description?: string}"
                                ),
                                "workspace_id", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Workspace ID (defaults to 'default')"
                                )
                        )
                ),
                new ToolSpec(
                        "update_task_status",
                        "Change status of a specific task. Validates dependencies — cannot complete a task with incomplete dependencies.",
                        Map.of(
                                "task_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "ID of task to update"
                                ),
                                "status", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "enum", List.of("TODO", "IN_PROGRESS", "DONE", "BLOCKED"),
                                        "description", "New status"
                                ),
                                "reason", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Optional reason for status change"
                                ),
                                "workspace_id", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Workspace ID (defaults to 'default')"
                                )
                        )
                ),
                new ToolSpec(
                        "move_task",
                        "Move task to new position in the plan. Updates task order for UI rendering.",
                        Map.of(
                                "task_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "ID of task to move"
                                ),
                                "position_type", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "enum", List.of("index", "before", "after", "end", "start"),
                                        "description", "Target position type"
                                ),
                                "position_value", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "For index: integer; for before/after: task_id; for end/start: not needed"
                                ),
                                "workspace_id", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Workspace ID (defaults to 'default')"
                                )
                        )
                ),
                new ToolSpec(
                        "add_task",
                        "Add new task to plan. Auto-generates ID and timestamp.",
                        Map.of(
                                "title", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "Task title/short description"
                                ),
                                "description", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Optional detailed description"
                                ),
                                "priority", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "enum", List.of("HIGH", "MEDIUM", "LOW"),
                                        "description", "Task priority. Default: MEDIUM"
                                ),
                                "dependencies", Map.of(
                                        "type", "array",
                                        "required", false,
                                        "description", "List of task IDs this task depends on"
                                ),
                                "position_type", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "enum", List.of("end", "start", "before", "after"),
                                        "description", "Where to place the new task (default: end)"
                                ),
                                "position_value", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Required for before/after — reference task ID"
                                ),
                                "workspace_id", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Workspace ID (defaults to 'default')"
                                )
                        )
                ),
                new ToolSpec(
                        "delete_task",
                        "Remove task from plan. Also removes this task from dependencies of other tasks.",
                        Map.of(
                                "task_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "ID of task to delete"
                                ),
                                "cascade", Map.of(
                                        "type", "boolean",
                                        "required", false,
                                        "description", "If true, also delete tasks that depend on this task. Default: false"
                                ),
                                "workspace_id", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Workspace ID (defaults to 'default')"
                                )
                        )
                ),
                new ToolSpec(
                        "update_task_priority",
                        "Change priority of a task. Affects sorting order in UI.",
                        Map.of(
                                "task_id", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "description", "ID of task"
                                ),
                                "priority", Map.of(
                                        "type", "string",
                                        "required", true,
                                        "enum", List.of("HIGH", "MEDIUM", "LOW"),
                                        "description", "New priority"
                                ),
                                "workspace_id", Map.of(
                                        "type", "string",
                                        "required", false,
                                        "description", "Workspace ID (defaults to 'default')"
                                )
                        )
                )
        );
    }

    // === Tool execution ===

    @SuppressWarnings("unchecked")
    public String callTool(String toolName, Map<String, Object> args) {
        if (args == null) args = Map.of();

        return switch (toolName) {
            case "read_plan" -> readPlan(args);
            case "add_tasks" -> addTasks(args);
            case "update_task_status" -> updateTaskStatus(args);
            case "move_task" -> moveTask(args);
            case "add_task" -> addTask(args);
            case "delete_task" -> deleteTask(args);
            case "update_task_priority" -> updateTaskPriority(args);
            default -> "ERROR: Unknown tool: " + toolName;
        };
    }

    private String readPlan(Map<String, Object> args) {
        String workspaceId = (String) args.getOrDefault("workspace_id", planService.getDefaultWorkspace());
        String format = (String) args.getOrDefault("format", "full");
        String statusFilterStr = (String) args.get("status_filter");
        TaskStatus statusFilter = null;
        if (statusFilterStr != null) {
            try {
                statusFilter = TaskStatus.valueOf(statusFilterStr);
            } catch (IllegalArgumentException e) {
                return "ERROR: Invalid status filter: " + statusFilterStr;
            }
        }

        if ("status_summary".equals(format)) {
            Map<String, Object> summary = planService.getPlanSummary(workspaceId, format, statusFilter);
            return formatStatusSummary(summary);
        }

        if ("tasks_only".equals(format)) {
            Map<String, Object> summary = planService.getPlanSummary(workspaceId, format, statusFilter);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tasks = (List<Map<String, Object>>) summary.get("tasks");
            return formatTasksListFromMap(tasks);
        }

        Map<String, Object> summary = planService.getPlanSummary(workspaceId, format, statusFilter);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        } catch (Exception e) {
            return "ERROR: Failed to serialize plan: " + e.getMessage();
        }
    }

    private String formatStatusSummary(Map<String, Object> summary) {
        return String.format("Plan: %s\nTotal tasks: %s\nBy status: %s",
                summary.get("name"), summary.get("totalTasks"), summary.get("byStatus"));
    }

    private String formatTasksListFromMap(List<Map<String, Object>> tasks) {
        if (tasks == null || tasks.isEmpty()) return "Plan is empty — no tasks.";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> t : tasks) {
            String statusIcon = switch ((String) t.get("status")) {
                case "TODO" -> "⬜";
                case "IN_PROGRESS" -> "🔄";
                case "DONE" -> "✅";
                case "BLOCKED" -> "🚫";
                default -> "❓";
            };
            String priorityIcon = switch ((String) t.get("priority")) {
                case "HIGH" -> "🔴";
                case "MEDIUM" -> "🟡";
                case "LOW" -> "🟢";
                default -> "⚪";
            };
            @SuppressWarnings("unchecked")
            List<String> deps = (List<String>) t.get("dependencies");
            sb.append(String.format("%s %s [%s] %s (id: %s)",
                    statusIcon, priorityIcon, t.get("status"), t.get("title"), t.get("id")));
            if (deps != null && !deps.isEmpty()) {
                sb.append(" depends on: ").append(String.join(", ", deps));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private String addTasks(Map<String, Object> args) {
        String workspaceId = (String) args.getOrDefault("workspace_id", planService.getDefaultWorkspace());
        Object tasksObj = args.get("tasks");
        if (!(tasksObj instanceof List<?> taskList) || taskList.isEmpty()) {
            return "ERROR: 'tasks' must be a non-empty array of objects with 'title' field";
        }

        List<PlanService.TaskRequest> requests = new ArrayList<>();
        for (Object item : taskList) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String title = (String) map.get("title");
            if (title == null || title.isBlank()) continue;
            String desc = (String) map.get("description");
            Priority priority = Priority.MEDIUM;
            try {
                String p = (String) map.get("priority");
                if (p != null) priority = Priority.valueOf(p.toUpperCase());
            } catch (Exception ignored) {}
            @SuppressWarnings("unchecked")
            List<String> deps = map.get("dependencies") instanceof List<?> dl
                    ? dl.stream().map(Object::toString).toList() : List.of();
            requests.add(new PlanService.TaskRequest(title, desc, priority, deps));
        }

        if (requests.isEmpty()) {
            return "ERROR: No valid tasks in array. Each must have a non-empty 'title'.";
        }

        try {
            List<Task> created = planService.addTasks(workspaceId, requests);
            return "OK: " + created.size() + " tasks added — " +
                    created.stream().map(Task::getTitle).reduce((a, b) -> a + ", " + b).orElse("");
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String updateTaskStatus(Map<String, Object> args) {
        String taskId = requireArg(args, "task_id");
        String statusStr = requireArg(args, "status");
        String reason = (String) args.get("reason");
        String workspaceId = (String) args.getOrDefault("workspace_id", planService.getDefaultWorkspace());

        TaskStatus status;
        try {
            status = TaskStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            return "ERROR: Invalid status: " + statusStr + ". Must be one of: TODO, IN_PROGRESS, DONE, BLOCKED";
        }

        try {
            Task task = planService.updateTaskStatus(workspaceId, taskId, status, reason);
            return "OK: Task '" + task.getTitle() + "' status changed to " + status +
                    (reason != null ? " (reason: " + reason + ")" : "") +
                    ". ID: " + taskId;
        } catch (IllegalStateException e) {
            return "ERROR: " + e.getMessage();
        } catch (NoSuchElementException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String moveTask(Map<String, Object> args) {
        String taskId = requireArg(args, "task_id");
        String positionType = requireArg(args, "position_type");
        String positionValue = (String) args.getOrDefault("position_value", "");
        String workspaceId = (String) args.getOrDefault("workspace_id", planService.getDefaultWorkspace());

        try {
            PlanService.MovePosition position = new PlanService.MovePosition(positionType, positionValue);
            planService.moveTask(workspaceId, taskId, position);
            return "OK: Task moved to " + positionType +
                    (positionValue.isEmpty() ? "" : " " + positionValue) +
                    ". ID: " + taskId;
        } catch (NoSuchElementException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String addTask(Map<String, Object> args) {
        String title = requireArg(args, "title");
        String description = (String) args.get("description");
        String priorityStr = (String) args.get("priority");
        String workspaceId = (String) args.getOrDefault("workspace_id", planService.getDefaultWorkspace());

        Priority priority = Priority.MEDIUM;
        if (priorityStr != null) {
            try {
                priority = Priority.valueOf(priorityStr);
            } catch (IllegalArgumentException e) {
                return "ERROR: Invalid priority: " + priorityStr + ". Must be HIGH, MEDIUM, or LOW";
            }
        }

        List<String> dependencies = new ArrayList<>();
        Object depsObj = args.get("dependencies");
        if (depsObj instanceof List) {
            for (Object d : (List<?>) depsObj) {
                if (d instanceof String) dependencies.add((String) d);
            }
        }

        String positionType = (String) args.getOrDefault("position_type", "end");
        String positionValue = (String) args.get("position_value");
        PlanService.PositionRequest position = null;
        if (!"end".equals(positionType)) {
            position = new PlanService.PositionRequest(positionType, positionValue);
        }

        try {
            Task task = planService.addTask(workspaceId, title, description, priority, dependencies, position);
            return "OK: Task added — '" + task.getTitle() + "' (ID: " + task.getId() +
                    ", priority: " + task.getPriority() +
                    ", dependencies: " + task.getDependencies().size() + ")";
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String deleteTask(Map<String, Object> args) {
        String taskId = requireArg(args, "task_id");
        boolean cascade = Boolean.TRUE.equals(args.get("cascade"));
        String workspaceId = (String) args.getOrDefault("workspace_id", planService.getDefaultWorkspace());

        try {
            planService.deleteTask(workspaceId, taskId, cascade);
            return "OK: Task deleted — " + taskId + (cascade ? " (cascade)" : "");
        } catch (IllegalStateException e) {
            return "ERROR: " + e.getMessage();
        } catch (NoSuchElementException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String updateTaskPriority(Map<String, Object> args) {
        String taskId = requireArg(args, "task_id");
        String priorityStr = requireArg(args, "priority");
        String workspaceId = (String) args.getOrDefault("workspace_id", planService.getDefaultWorkspace());

        Priority priority;
        try {
            priority = Priority.valueOf(priorityStr);
        } catch (IllegalArgumentException e) {
            return "ERROR: Invalid priority: " + priorityStr + ". Must be HIGH, MEDIUM, or LOW";
        }

        try {
            Task task = planService.updateTaskPriority(workspaceId, taskId, priority);
            return "OK: Task '" + task.getTitle() + "' priority changed to " + priority + ". ID: " + taskId;
        } catch (NoSuchElementException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // === Helpers ===

    private String requireArg(Map<String, Object> args, String name) {
        String value = (String) args.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return value;
    }

    private String formatTasksList(List<Task> tasks) {
        if (tasks.isEmpty()) return "Plan is empty — no tasks.";

        StringBuilder sb = new StringBuilder();
        for (Task task : tasks) {
            String statusIcon = switch (task.getStatus()) {
                case TODO -> "⬜";
                case IN_PROGRESS -> "🔄";
                case DONE -> "✅";
                case BLOCKED -> "🚫";
            };
            String priorityIcon = switch (task.getPriority()) {
                case HIGH -> "🔴";
                case MEDIUM -> "🟡";
                case LOW -> "🟢";
            };
            sb.append(String.format("%s %s [%s] %s (id: %s)",
                    statusIcon, priorityIcon, task.getStatus(), task.getTitle(), task.getId()));
            if (!task.getDependencies().isEmpty()) {
                sb.append(" depends on: ").append(String.join(", ", task.getDependencies()));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // === ToolSpec record ===

    public record ToolSpec(String name, String description, Map<String, Object> properties) {}
}
