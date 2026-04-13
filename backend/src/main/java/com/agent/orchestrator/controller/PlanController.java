package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.service.PlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    // === Read plan ===

    @GetMapping
    public Plan getPlan(@RequestParam(defaultValue = "default") String workspaceId,
                        @RequestParam(defaultValue = "full") String format) {
        return planService.getPlan(workspaceId);
    }

    // === Add task ===

    @PostMapping("/tasks")
    public Task addTask(@RequestBody AddTaskRequest request) {
        String workspaceId = request.workspaceId() != null ? request.workspaceId() : planService.getDefaultWorkspace();
        return planService.addTask(
                workspaceId,
                request.title(),
                request.description(),
                request.priority(),
                request.dependencies(),
                request.position()
        );
    }

    // === Batch add tasks ===

    @PostMapping("/tasks/batch")
    public ResponseEntity<Map<String, Object>> addTasks(@RequestBody BatchAddRequest request) {
        String workspaceId = request.workspaceId() != null ? request.workspaceId() : planService.getDefaultWorkspace();
        List<PlanService.TaskRequest> taskRequests = request.tasks().stream()
                .map(t -> new PlanService.TaskRequest(t.title(), t.description(), t.priority(), t.dependencies()))
                .toList();
        List<Task> created = planService.addTasks(workspaceId, taskRequests);
        return ResponseEntity.ok(Map.of("status", "created", "count", created.size(),
                "tasks", created.stream().map(t -> Map.of("id", t.getId(), "title", t.getTitle())).toList()));
    }

    // === Update task status ===

    @PutMapping("/tasks/{taskId}/status")
    public Task updateTaskStatus(@PathVariable String taskId,
                                 @RequestBody UpdateStatusRequest request) {
        String workspaceId = request.workspaceId() != null ? request.workspaceId() : planService.getDefaultWorkspace();
        return planService.updateTaskStatus(workspaceId, taskId, request.status(), request.reason());
    }

    // === Update task priority ===

    @PutMapping("/tasks/{taskId}/priority")
    public Task updateTaskPriority(@PathVariable String taskId,
                                   @RequestBody UpdatePriorityRequest request) {
        String workspaceId = request.workspaceId() != null ? request.workspaceId() : planService.getDefaultWorkspace();
        return planService.updateTaskPriority(workspaceId, taskId, request.priority());
    }

    // === Move task ===

    @PutMapping("/tasks/{taskId}/move")
    public ResponseEntity<Map<String, String>> moveTask(@PathVariable String taskId,
                                                         @RequestBody MoveTaskRequest request) {
        String workspaceId = request.workspaceId() != null ? request.workspaceId() : planService.getDefaultWorkspace();
        PlanService.MovePosition position = new PlanService.MovePosition(request.position().type(), request.position().value());
        planService.moveTask(workspaceId, taskId, position);
        return ResponseEntity.ok(Map.of("status", "moved", "taskId", taskId));
    }

    // === Acceptance Criteria ===

    @PutMapping("/tasks/{taskId}/criteria")
    public ResponseEntity<Map<String, String>> updateCriteria(@PathVariable String taskId,
                                                               @RequestBody CriteriaRequest request) {
        String workspaceId = planService.getDefaultWorkspace();
        planService.updateAcceptanceCriteria(workspaceId, taskId, request.criteria(), request.met());
        return ResponseEntity.ok(Map.of("status", "updated", "taskId", taskId));
    }

    // === Link/unlink task to node ===

    @PutMapping("/tasks/{taskId}/link")
    public ResponseEntity<Map<String, String>> linkTaskToNode(@PathVariable String taskId,
                                                               @RequestBody LinkTaskRequest request) {
        String workspaceId = planService.getDefaultWorkspace();
        planService.linkTaskToNode(workspaceId, taskId, request.nodeId());
        return ResponseEntity.ok(Map.of("status", "linked", "taskId", taskId,
                "nodeId", request.nodeId() != null ? request.nodeId() : "none"));
    }

    // === Delete task ===

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, String>> deleteTask(@PathVariable String taskId,
                                                           @RequestParam(defaultValue = "false") boolean cascade) {
        String workspaceId = planService.getDefaultWorkspace();
        planService.deleteTask(workspaceId, taskId, cascade);
        return ResponseEntity.ok(Map.of("status", "deleted", "taskId", taskId));
    }

    // === Request DTOs ===

    public record AddTaskRequest(String workspaceId, String title, String description,
                                  Priority priority, java.util.List<String> dependencies,
                                  PlanService.PositionRequest position) {}

    public record BatchAddRequest(String workspaceId, List<BatchTask> tasks) {}
    public record BatchTask(String title, String description, Priority priority, java.util.List<String> dependencies) {}

    public record UpdateStatusRequest(String workspaceId, TaskStatus status, String reason) {}

    public record UpdatePriorityRequest(String workspaceId, Priority priority) {}

    public record MoveTaskRequest(String workspaceId, PlanService.PositionRequest position) {}
    public record LinkTaskRequest(String nodeId) {}
    public record CriteriaRequest(List<String> criteria, List<Boolean> met) {}
}
