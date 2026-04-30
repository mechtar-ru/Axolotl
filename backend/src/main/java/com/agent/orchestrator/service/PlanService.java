package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.PlanRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private static final String DEFAULT_WORKSPACE = "default";

    private final PlanRepository planRepository;
    private final ExecutionWebSocketHandler webSocketHandler;

    public PlanService(PlanRepository planRepository, ExecutionWebSocketHandler webSocketHandler) {
        this.planRepository = planRepository;
        this.webSocketHandler = webSocketHandler;
        initDefaultPlan();
    }

    // === Core operations ===

    public Plan getPlan(String workspaceId) {
        Plan plan = planRepository.findByWorkspaceId(workspaceId);
        if (plan == null) {
            plan = createDefaultPlan(workspaceId);
        }
        return plan;
    }

    public List<String> listWorkspaces() {
        return planRepository.findAllWorkspaceIds();
    }

    public List<Plan> getAllPlans(String workspaceId) {
        return planRepository.findAll(workspaceId);
    }

    public Map<String, Object> getPlanSummary(String workspaceId, String format, TaskStatus statusFilter) {
        Plan plan = getPlan(workspaceId);
        List<Task> tasks = plan.getTasks();
        if (statusFilter != null) {
            tasks = tasks.stream().filter(t -> t.getStatus() == statusFilter).toList();
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", plan.getId());
        summary.put("workspaceId", plan.getWorkspaceId());
        summary.put("name", plan.getName());
        summary.put("totalTasks", tasks.size());

        if ("status_summary".equals(format)) {
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (TaskStatus s : TaskStatus.values()) byStatus.put(s.name(), 0L);
            for (Task t : tasks) {
                byStatus.merge(t.getStatus().name(), 1L, Long::sum);
            }
            summary.put("byStatus", byStatus);
        }

        if ("tasks_only".equals(format) || "full".equals(format)) {
            List<Map<String, Object>> taskList = new ArrayList<>();
            for (Task t : tasks) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("id", t.getId());
                tm.put("title", t.getTitle());
                tm.put("status", t.getStatus().name());
                tm.put("priority", t.getPriority().name());
                tm.put("dependencies", t.getDependencies());
                tm.put("nodeId", t.getNodeId());
                tm.put("reason", t.getReason());
                tm.put("order", t.getOrder());
                tm.put("acceptanceCriteria", t.getAcceptanceCriteria());
                tm.put("acceptanceCriteriaMet", t.getAcceptanceCriteriaMet());
                taskList.add(tm);
            }
            summary.put("tasks", taskList);
        }

        return summary;
    }

    public Plan updatePlan(Plan plan) {
        plan.touch();
        planRepository.save(plan);
        notifyPlanUpdated(plan);
        return plan;
    }

    public Task addTask(String workspaceId, String title, String description,
                        Priority priority, List<String> dependencies, PositionRequest position, String schemaId) {
        Plan plan = getPlan(workspaceId);

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Task title cannot be empty");
        }

        Task task = new Task(title);
        task.setDescription(description != null ? description : "");
        task.setPriority(priority != null ? priority : Priority.MEDIUM);
        task.setDependencies(dependencies != null ? dependencies : new ArrayList<>());
        task.setSchemaId(schemaId);

        // Validate dependencies exist
        Set<String> existingIds = plan.getTasks().stream().map(Task::getId).collect(Collectors.toSet());
        for (String depId : task.getDependencies()) {
            if (!existingIds.contains(depId)) {
                throw new IllegalArgumentException("Dependency task not found: " + depId);
            }
        }

        // Determine order
        int maxOrder = plan.getTasks().stream().mapToInt(Task::getOrder).max().orElse(-1);
        task.setOrder(maxOrder + 1);

        // Insert at position
        if (position != null) {
            List<Task> tasks = plan.getTasks();
            int insertIndex;
            switch (position.type()) {
                case "start" -> insertIndex = 0;
                case "end" -> insertIndex = tasks.size();
                case "before" -> {
                    int idx = indexOfTask(tasks, position.value());
                    insertIndex = idx >= 0 ? idx : tasks.size();
                }
                case "after" -> {
                    int idx = indexOfTask(tasks, position.value());
                    insertIndex = idx >= 0 ? idx + 1 : tasks.size();
                }
                default -> insertIndex = tasks.size();
            }

            plan.getTasks().add(insertIndex, task);
            reorderTasksFromIndex(plan.getTasks(), 0);
        } else {
            plan.getTasks().add(task);
            task.setOrder(maxOrder + 1);
        }

        plan.touch();
        planRepository.save(plan);
        notifyPlanUpdated(plan);

        log.info("Добавлена задача: {} (ID: {})", title, task.getId());
        return task;
    }

    public List<Task> addTasks(String workspaceId, List<TaskRequest> requests) {
        Plan plan = getPlan(workspaceId);
        int maxOrder = plan.getTasks().stream().mapToInt(Task::getOrder).max().orElse(-1);
        List<Task> created = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            TaskRequest req = requests.get(i);
            if (req.title() == null || req.title().isBlank()) continue;

            Task task = new Task(req.title());
            task.setDescription(req.description() != null ? req.description() : "");
            task.setPriority(req.priority() != null ? req.priority() : Priority.MEDIUM);
            task.setDependencies(req.dependencies() != null ? req.dependencies() : new ArrayList<>());
            task.setOrder(maxOrder + 1 + i);

            plan.getTasks().add(task);
            created.add(task);
            log.info("[batch] Добавлена задача: {} (ID: {})", task.getTitle(), task.getId());
        }

        plan.touch();
        planRepository.save(plan);
        notifyPlanUpdated(plan);
        return created;
    }

    public Task updateTaskStatus(String workspaceId, String taskId, TaskStatus status, String reason) {
        Plan plan = getPlan(workspaceId);
        Task task = findTaskById(plan, taskId);
        if (task == null) {
            throw new NoSuchElementException("Task not found: " + taskId);
        }

        // Validate dependencies when transitioning to DONE
        if (status == TaskStatus.DONE) {
            validateDependencies(plan, taskId, status);
            // Check acceptance criteria
            Task t = findTaskById(plan, taskId);
            if (t != null && !t.allCriteriaMet()) {
                throw new IllegalStateException(
                        "Cannot mark as DONE — acceptance criteria not met: " +
                        t.getCriteriaMetCount() + "/" + t.getAcceptanceCriteria().size());
            }
        }

        task.setStatus(status);
        task.setReason(reason != null ? reason : task.getReason());
        task.touch();
        plan.touch();
        planRepository.save(plan);
        notifyPlanUpdated(plan);

        log.info("Статус задачи {} изменён на {}{}", taskId, status,
                reason != null ? " (причина: " + reason + ")" : "");
        return task;
    }

    public void moveTask(String workspaceId, String taskId, MovePosition position) {
        Plan plan = getPlan(workspaceId);
        int currentIndex = indexOfTask(plan.getTasks(), taskId);
        if (currentIndex < 0) {
            throw new NoSuchElementException("Task not found: " + taskId);
        }

        List<Task> tasks = plan.getTasks();
        Task task = tasks.remove(currentIndex);

        int targetIndex;
        switch (position.type()) {
            case "start" -> targetIndex = 0;
            case "end" -> targetIndex = tasks.size();
            case "before" -> {
                int idx = indexOfTask(tasks, position.value());
                targetIndex = idx >= 0 ? idx : tasks.size();
            }
            case "after" -> {
                int idx = indexOfTask(tasks, position.value());
                targetIndex = idx >= 0 ? idx + 1 : tasks.size();
            }
            case "index" -> {
                try {
                    targetIndex = Integer.parseInt(position.value());
                    targetIndex = Math.max(0, Math.min(targetIndex, tasks.size()));
                } catch (NumberFormatException e) {
                    targetIndex = tasks.size();
                }
            }
            default -> targetIndex = tasks.size();
        }

        tasks.add(targetIndex, task);
        reorderTasksFromIndex(tasks, 0);
        task.touch();
        plan.touch();
        planRepository.save(plan);
        notifyPlanUpdated(plan);

        log.info("Задача {} перемещена на позицию {}", taskId, targetIndex);
    }

    public void deleteTask(String workspaceId, String taskId, boolean cascade) {
        Plan plan = getPlan(workspaceId);
        Task task = findTaskById(plan, taskId);
        if (task == null) {
            throw new NoSuchElementException("Task not found: " + taskId);
        }

        // Find tasks that depend on this one
        List<Task> dependents = plan.getTasks().stream()
                .filter(t -> t.getDependencies().contains(taskId))
                .collect(Collectors.toList());

        if (!cascade && !dependents.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot delete task " + taskId + " — " + dependents.size() +
                    " task(s) depend on it: " +
                    dependents.stream().map(Task::getTitle).collect(Collectors.joining(", ")) +
                    ". Use cascade=true to force delete.");
        }

        if (cascade) {
            // Delete dependents first (recursive)
            for (Task dep : new ArrayList<>(dependents)) {
                deleteTask(workspaceId, dep.getId(), true);
                // Reload plan after recursive delete
                plan = planRepository.findByWorkspaceId(workspaceId);
            }
        } else {
            // Remove this task from dependencies of other tasks
            for (Task t : plan.getTasks()) {
                if (t.getDependencies().remove(taskId)) {
                    t.touch();
                }
            }
        }

        plan.getTasks().removeIf(t -> t.getId().equals(taskId));
        reorderTasksFromIndex(plan.getTasks(), 0);
        plan.touch();
        planRepository.save(plan);
        notifyPlanUpdated(plan);

        log.info("Удалена задача: {}{}", taskId, cascade ? " (cascade)" : "");
    }

    public Task updateTaskPriority(String workspaceId, String taskId, Priority priority) {
        Plan plan = getPlan(workspaceId);
        Task task = findTaskById(plan, taskId);
        if (task == null) {
            throw new NoSuchElementException("Task not found: " + taskId);
        }

        task.setPriority(priority);
        task.touch();
        plan.touch();
        planRepository.save(plan);
        notifyPlanUpdated(plan);

        log.info("Приоритет задачи {} изменён на {}", taskId, priority);
        return task;
    }

    public Task linkTaskToNode(String workspaceId, String taskId, String nodeId) {
        Plan plan = getPlan(workspaceId);
        Task task = findTaskById(plan, taskId);
        if (task == null) {
            throw new NoSuchElementException("Task not found: " + taskId);
        }

        task.setNodeId(nodeId);
        task.touch();
        plan.touch();
        planRepository.save(plan);
        notifyPlanUpdated(plan);

        log.info("Задача {} связана с узлом {}", taskId,
                nodeId != null ? nodeId : "разорвана");
        return task;
    }

    public Task updateAcceptanceCriteria(String workspaceId, String taskId,
                                          List<String> criteria, List<Boolean> met) {
        Plan plan = getPlan(workspaceId);
        Task task = findTaskById(plan, taskId);
        if (task == null) {
            throw new NoSuchElementException("Task not found: " + taskId);
        }

        if (criteria != null) {
            task.setAcceptanceCriteria(new ArrayList<>(criteria));
        }
        if (met != null) {
            task.setAcceptanceCriteriaMet(new ArrayList<>(met));
        }
        // Ensure met list matches criteria list size
        if (task.getAcceptanceCriteria() != null) {
            int size = task.getAcceptanceCriteria().size();
            if (task.getAcceptanceCriteriaMet() == null) {
                task.setAcceptanceCriteriaMet(new ArrayList<>(Collections.nCopies(size, false)));
            }
            while (task.getAcceptanceCriteriaMet().size() < size) {
                task.getAcceptanceCriteriaMet().add(false);
            }
        }
        task.touch();
        plan.touch();
        planRepository.save(plan);
        notifyPlanUpdated(plan);

        log.info("Критерии приёмки задачи {} обновлены: {}/{}", taskId,
                task.getCriteriaMetCount(), task.getAcceptanceCriteria().size());
        return task;
    }

    // === Helper methods ===

    private Task findTaskById(Plan plan, String taskId) {
        return plan.getTasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst()
                .orElse(null);
    }

    private int indexOfTask(List<Task> tasks, String taskId) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(taskId)) return i;
        }
        return -1;
    }

    private void reorderTasksFromIndex(List<Task> tasks, int fromIndex) {
        for (int i = fromIndex; i < tasks.size(); i++) {
            tasks.get(i).setOrder(i);
        }
    }

    private void validateDependencies(Plan plan, String taskId, TaskStatus newStatus) {
        Task task = findTaskById(plan, taskId);
        if (task == null || task.getDependencies().isEmpty()) return;

        List<String> incompleteDeps = new ArrayList<>();
        for (String depId : task.getDependencies()) {
            Task dep = findTaskById(plan, depId);
            if (dep != null && dep.getStatus() != TaskStatus.DONE) {
                incompleteDeps.add(depId + " (" + dep.getTitle() + ", status: " + dep.getStatus() + ")");
            }
        }

        if (!incompleteDeps.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot mark task as DONE — incomplete dependencies: " +
                    String.join(", ", incompleteDeps));
        }
    }

    private Plan createDefaultPlan(String workspaceId) {
        Plan plan = new Plan(workspaceId, "План");
        planRepository.save(plan);
        log.info("Создан план по умолчанию для workspace: {}", workspaceId);
        return plan;
    }

    protected void initDefaultPlan() {
        try {
            Plan existing = planRepository.findByWorkspaceId(DEFAULT_WORKSPACE);
            if (existing == null) {
                createDefaultPlan(DEFAULT_WORKSPACE);
                log.info("Создан новый план для workspace: {}", DEFAULT_WORKSPACE);
            } else {
                log.info("Загружен существующий план для workspace: {} ({} задач)",
                        DEFAULT_WORKSPACE, existing.getTasks().size());
            }
        } catch (Exception e) {
            log.error("Ошибка инициализации плана: {}", e.getMessage());
        }
    }

    protected void notifyPlanUpdated(Plan plan) {
        if (webSocketHandler != null) {
            webSocketHandler.sendPlanUpdated(plan.getWorkspaceId(), plan);
        }
    }

    // === Hierarchy operations ===

    public List<Plan> getChildPlans(String parentPlanId) {
        return planRepository.findByParentId(parentPlanId);
    }

    public Plan createSubPlan(String workspaceId, String parentPlanId, String name, PlanLevel level, String schemaId) {
        Plan sub = new Plan(workspaceId, name);
        sub.setParentId(parentPlanId);
        sub.setLevel(level);
        sub.setSchemaId(schemaId);
        planRepository.save(sub);
        log.info("Создан подплан '{}' (level={}) для parent={}", name, level, parentPlanId);
        return sub;
    }

    public Plan getPlanBySchemaId(String schemaId) {
        return planRepository.findBySchemaId(schemaId);
    }

    public Plan getPlanById(String planId) {
        return planRepository.findById(planId);
    }

    public Plan importSchemaAsSubPlan(String workspaceId, String parentPlanId, String schemaId) {
        Plan existing = planRepository.findBySchemaId(schemaId);
        if (existing != null) {
            return existing;
        }
        Plan sub = new Plan(workspaceId, "Schema plan");
        sub.setParentId(parentPlanId);
        sub.setLevel(PlanLevel.SUBSCHEMA);
        sub.setSchemaId(schemaId);
        planRepository.save(sub);
        return sub;
    }

    public String getDefaultWorkspace() {
        return DEFAULT_WORKSPACE;
    }

    // === Request DTOs ===

    public record PositionRequest(String type, String value) {}
    public record MovePosition(String type, String value) {}
    public record TaskRequest(String title, String description, Priority priority, List<String> dependencies) {}
}
