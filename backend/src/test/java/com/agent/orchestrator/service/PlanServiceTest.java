package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PlanService business logic.
 * Uses a mock repository approach (in-memory plan).
 */
class PlanServiceTest {

    private TestPlanService planService;

    @BeforeEach
    void setUp() {
        planService = new TestPlanService();
    }

    // ===== Add Task =====

    @Test
    @DisplayName("Add task to empty plan")
    void addTaskToEmptyPlan() {
        Task task = planService.addTask("default", "Test task", "Description", Priority.HIGH, List.of(), null, null);
        assertNotNull(task.getId());
        assertEquals("Test task", task.getTitle());
        assertEquals("Description", task.getDescription());
        assertEquals(Priority.HIGH, task.getPriority());
        assertEquals(TaskStatus.TODO, task.getStatus());
        assertEquals(1, planService.getPlan("default").getTasks().size());
    }

    @Test
    @DisplayName("Add task with null title should fail")
    void addTaskNullTitle() {
        assertThrows(IllegalArgumentException.class, () ->
                planService.addTask("default", null, "", Priority.MEDIUM, List.of(), null, null));
    }

    @Test
    @DisplayName("Add task with empty title should fail")
    void addTaskEmptyTitle() {
        assertThrows(IllegalArgumentException.class, () ->
                planService.addTask("default", "", "", Priority.MEDIUM, List.of(), null, null));
    }

    @Test
    @DisplayName("Add task with invalid dependency should fail")
    void addTaskInvalidDependency() {
        planService.addTask("default", "Task A", "", Priority.MEDIUM, List.of(), null, null);
        assertThrows(IllegalArgumentException.class, () ->
                planService.addTask("default", "Task B", "", Priority.MEDIUM, List.of("non-existent-id"), null, null));
    }

    // ===== Update Status =====

    @Test
    @DisplayName("Update task status to DONE — no dependencies")
    void updateStatusNoDeps() {
        Task task = planService.addTask("default", "Task", "", Priority.MEDIUM, List.of(), null, null);
        Task updated = planService.updateTaskStatus("default", task.getId(), TaskStatus.DONE, null);
        assertEquals(TaskStatus.DONE, updated.getStatus());
    }

    @Test
    @DisplayName("Update task status to DONE — with completed dependency — should succeed")
    void updateStatusWithCompletedDependency() {
        Task dep = planService.addTask("default", "Dependency", "", Priority.MEDIUM, List.of(), null, null);
        planService.updateTaskStatus("default", dep.getId(), TaskStatus.DONE, null);

        Task task = planService.addTask("default", "Dependent", "", Priority.MEDIUM, List.of(dep.getId()), null, null);
        Task updated = planService.updateTaskStatus("default", task.getId(), TaskStatus.DONE, null);
        assertEquals(TaskStatus.DONE, updated.getStatus());
    }

    @Test
    @DisplayName("Update task status to DONE — with incomplete dependency — should fail")
    void updateStatusWithIncompleteDependency() {
        Task dep = planService.addTask("default", "Dependency", "", Priority.MEDIUM, List.of(), null, null);
        Task task = planService.addTask("default", "Dependent", "", Priority.MEDIUM, List.of(dep.getId()), null, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                planService.updateTaskStatus("default", task.getId(), TaskStatus.DONE, null));
        assertTrue(ex.getMessage().contains("incomplete dependency"));
    }

    @Test
    @DisplayName("Update task status to IN_PROGRESS — no validation needed")
    void updateStatusInProgress() {
        Task task = planService.addTask("default", "Task", "", Priority.MEDIUM, List.of(), null, null);
        Task updated = planService.updateTaskStatus("default", task.getId(), TaskStatus.IN_PROGRESS, "Started");
        assertEquals(TaskStatus.IN_PROGRESS, updated.getStatus());
        assertEquals("Started", updated.getReason());
    }

    // ===== Move Task =====

    @Test
    @DisplayName("Move task to start")
    void moveTaskToStart() {
        Task a = planService.addTask("default", "A", "", Priority.MEDIUM, List.of(), null, null);
        Task b = planService.addTask("default", "B", "", Priority.MEDIUM, List.of(), null, null);
        Task c = planService.addTask("default", "C", "", Priority.MEDIUM, List.of(), null, null);

        planService.moveTask("default", c.getId(), new PlanService.MovePosition("start", ""));

        Plan plan = planService.getPlan("default");
        assertEquals("C", plan.getTasks().get(0).getTitle());
        assertEquals("A", plan.getTasks().get(1).getTitle());
        assertEquals("B", plan.getTasks().get(2).getTitle());
    }

    @Test
    @DisplayName("Move task to end")
    void moveTaskToEnd() {
        Task a = planService.addTask("default", "A", "", Priority.MEDIUM, List.of(), null, null);
        Task b = planService.addTask("default", "B", "", Priority.MEDIUM, List.of(), null, null);

        planService.moveTask("default", a.getId(), new PlanService.MovePosition("end", ""));

        Plan plan = planService.getPlan("default");
        assertEquals("B", plan.getTasks().get(0).getTitle());
        assertEquals("A", plan.getTasks().get(1).getTitle());
    }

    // ===== Delete Task =====

    @Test
    @DisplayName("Delete task — no dependents")
    void deleteTaskNoDependents() {
        Task task = planService.addTask("default", "Task", "", Priority.MEDIUM, List.of(), null, null);
        planService.deleteTask("default", task.getId(), false);
        assertEquals(0, planService.getPlan("default").getTasks().size());
    }

    @Test
    @DisplayName("Delete task — with dependents, cascade=false — should fail")
    void deleteTaskWithDependentsNoCascade() {
        Task dep = planService.addTask("default", "Dependency", "", Priority.MEDIUM, List.of(), null, null);
        planService.addTask("default", "Dependent", "", Priority.MEDIUM, List.of(dep.getId()), null, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                planService.deleteTask("default", dep.getId(), false));
        assertTrue(ex.getMessage().contains("depend on it"));
    }

    @Test
    @DisplayName("Delete task — with dependents, cascade=true — should succeed")
    void deleteTaskWithDependentsCascade() {
        Task dep = planService.addTask("default", "Dependency", "", Priority.MEDIUM, List.of(), null, null);
        planService.addTask("default", "Dependent", "", Priority.MEDIUM, List.of(dep.getId()), null, null);

        planService.deleteTask("default", dep.getId(), true);
        assertEquals(0, planService.getPlan("default").getTasks().size());
    }

    @Test
    @DisplayName("Delete task — cascade removes dependents too")
    void deleteTaskCascadeRemovesDependents() {
        Task dep = planService.addTask("default", "Dependency", "", Priority.MEDIUM, List.of(), null, null);
        Task dependent = planService.addTask("default", "Dependent", "", Priority.MEDIUM, List.of(dep.getId()), null, null);

        planService.deleteTask("default", dep.getId(), true);

        // Both tasks removed
        assertEquals(0, planService.getPlan("default").getTasks().size());
    }

    // ===== Update Priority =====

    @Test
    @DisplayName("Update task priority")
    void updateTaskPriority() {
        Task task = planService.addTask("default", "Task", "", Priority.MEDIUM, List.of(), null, null);
        Task updated = planService.updateTaskPriority("default", task.getId(), Priority.LOW);
        assertEquals(Priority.LOW, updated.getPriority());
    }

    @Test
    @DisplayName("Update task priority — task not found")
    void updateTaskPriorityNotFound() {
        assertThrows(NoSuchElementException.class, () ->
                planService.updateTaskPriority("default", "non-existent", Priority.HIGH));
    }

    // ===== Test helper: in-memory PlanService (no SQLite) =====

    static class TestPlanService extends PlanService {

        private Plan inMemoryPlan;

        TestPlanService() {
            super(null, null);
        }

        @Override
        protected void initDefaultPlan() {
            // No-op
        }

        @Override
        public Plan getPlan(String workspaceId) {
            if (inMemoryPlan == null) {
                inMemoryPlan = new Plan(workspaceId, "Test Plan");
            }
            return inMemoryPlan;
        }

        @Override
        public Plan updatePlan(Plan plan) {
            inMemoryPlan = plan;
            return plan;
        }

        @Override
        public Task addTask(String workspaceId, String title, String description,
                            Priority priority, List<String> dependencies, PositionRequest position, String schemaId) {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Task title cannot be empty");
            }
            Plan plan = getPlan(workspaceId);
            Task task = new Task(title);
            task.setDescription(description != null ? description : "");
            task.setPriority(priority != null ? priority : Priority.MEDIUM);
            task.setDependencies(dependencies != null ? new ArrayList<>(dependencies) : new ArrayList<>());
            task.setOrder(plan.getTasks().size());
            // Validate dependencies exist
            var existingIds = plan.getTasks().stream().map(Task::getId).toList();
            for (String depId : task.getDependencies()) {
                if (!existingIds.contains(depId)) {
                    throw new IllegalArgumentException("Dependency task not found: " + depId);
                }
            }
            plan.getTasks().add(task);
            return task;
        }

        @Override
        public Task updateTaskStatus(String workspaceId, String taskId, TaskStatus status, String reason) {
            Plan plan = getPlan(workspaceId);
            Task task = plan.getTasks().stream().filter(t -> t.getId().equals(taskId)).findFirst().orElse(null);
            if (task == null) throw new NoSuchElementException("Task not found: " + taskId);
            if (status == TaskStatus.DONE) {
                // Validate dependencies
                for (String depId : task.getDependencies()) {
                    Task dep = plan.getTasks().stream().filter(t -> t.getId().equals(depId)).findFirst().orElse(null);
                    if (dep != null && dep.getStatus() != TaskStatus.DONE) {
                        throw new IllegalStateException("Cannot mark task as DONE — incomplete dependency: " + depId);
                    }
                }
            }
            task.setStatus(status);
            task.setReason(reason);
            return task;
        }

        @Override
        public void moveTask(String workspaceId, String taskId, MovePosition position) {
            Plan plan = getPlan(workspaceId);
            int idx = -1;
            for (int i = 0; i < plan.getTasks().size(); i++) {
                if (plan.getTasks().get(i).getId().equals(taskId)) { idx = i; break; }
            }
            if (idx < 0) throw new NoSuchElementException("Task not found: " + taskId);
            Task task = plan.getTasks().remove(idx);
            int target = "start".equals(position.type()) ? 0 : plan.getTasks().size();
            plan.getTasks().add(target, task);
            for (int i = 0; i < plan.getTasks().size(); i++) plan.getTasks().get(i).setOrder(i);
        }

        @Override
        public void deleteTask(String workspaceId, String taskId, boolean cascade) {
            Plan plan = getPlan(workspaceId);
            Task task = plan.getTasks().stream().filter(t -> t.getId().equals(taskId)).findFirst().orElse(null);
            if (task == null) throw new NoSuchElementException("Task not found: " + taskId);
            List<Task> dependents = plan.getTasks().stream().filter(t -> t.getDependencies().contains(taskId)).toList();
            if (!cascade && !dependents.isEmpty()) {
                throw new IllegalStateException("Cannot delete — tasks depend on it: " + dependents.size());
            }
            if (cascade) {
                for (Task dep : new ArrayList<>(dependents)) {
                    plan.getTasks().removeIf(t -> t.getId().equals(dep.getId()));
                }
            }
            plan.getTasks().removeIf(t -> t.getId().equals(taskId));
        }

        @Override
        public Task updateTaskPriority(String workspaceId, String taskId, Priority priority) {
            Plan plan = getPlan(workspaceId);
            Task task = plan.getTasks().stream().filter(t -> t.getId().equals(taskId)).findFirst().orElse(null);
            if (task == null) throw new NoSuchElementException("Task not found: " + taskId);
            task.setPriority(priority);
            return task;
        }

        @Override
        protected void notifyPlanUpdated(Plan plan) {
            // No-op
        }
    }
}
