package com.agent.orchestrator.repository;

import com.agent.orchestrator.graph.model.GraphPlan;
import com.agent.orchestrator.graph.repository.Neo4jPlanDataRepository;
import com.agent.orchestrator.model.Plan;
import com.agent.orchestrator.model.PlanLevel;
import com.agent.orchestrator.model.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class PlanRepository {

    private static final Logger log = LoggerFactory.getLogger(PlanRepository.class);
    private final Neo4jPlanDataRepository neo4jRepo;
    private final ObjectMapper mapper;

    public PlanRepository(Neo4jPlanDataRepository neo4jRepo) {
        this.neo4jRepo = neo4jRepo;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    public void save(Plan plan) {
        try {
            neo4jRepo.save(toGraph(plan));
        } catch (Exception e) {
            throw new RuntimeException("Ошибка сохранения плана: " + e.getMessage(), e);
        }
    }

    public Plan findByWorkspaceId(String workspaceId) {
        try {
            return neo4jRepo.findByWorkspaceId(workspaceId).map(this::toPoco).orElse(null);
        } catch (Exception e) {
            log.error("Ошибка чтения плана: {}", e.getMessage());
            return null;
        }
    }

    public List<Plan> findAll(String workspaceId) {
        List<Plan> plans = new ArrayList<>();
        try {
            for (GraphPlan g : neo4jRepo.findAllByWorkspaceId(workspaceId)) {
                plans.add(toPoco(g));
            }
        } catch (Exception e) {
            log.error("Ошибка чтения планов: {}", e.getMessage());
        }
        return plans;
    }

    public List<String> findAllWorkspaceIds() {
        try {
            return neo4jRepo.findAllWorkspaceIds();
        } catch (Exception e) {
            log.error("Ошибка чтения workspaces: {}", e.getMessage());
            return List.of();
        }
    }

    public void delete(String id) {
        try {
            neo4jRepo.deleteById(id);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка удаления плана: " + e.getMessage(), e);
        }
    }

    public List<Plan> findByParentId(String parentId) {
        List<Plan> plans = new ArrayList<>();
        try {
            for (GraphPlan g : neo4jRepo.findByParentIdOrderByCreatedAt(parentId)) {
                plans.add(toPoco(g));
            }
        } catch (Exception e) {
            log.error("Ошибка чтения дочерних планов: {}", e.getMessage());
        }
        return plans;
    }

    public Plan findBySchemaId(String schemaId) {
        try {
            return neo4jRepo.findBySchemaId(schemaId).map(this::toPoco).orElse(null);
        } catch (Exception e) {
            log.error("Ошибка чтения плана по schemaId: {}", e.getMessage());
            return null;
        }
    }

    public Plan findById(String id) {
        try {
            return neo4jRepo.findById(id).map(this::toPoco).orElse(null);
        } catch (Exception e) {
            log.error("Ошибка чтения плана: {}", e.getMessage());
            return null;
        }
    }

    private GraphPlan toGraph(Plan plan) {
        GraphPlan g = new GraphPlan();
        g.setId(plan.getId());
        g.setWorkspaceId(plan.getWorkspaceId());
        g.setName(plan.getName());
        g.setParentId(plan.getParentId());
        g.setSchemaId(plan.getSchemaId());
        g.setLevel(plan.getLevel() != null ? plan.getLevel().name() : "PROJECT");
        try {
            g.setTasksJson(mapper.writeValueAsString(plan.getTasks()));
        } catch (Exception e) {
            log.error("Error serializing tasks: {}", e.getMessage());
            g.setTasksJson("[]");
        }
        g.setCreatedAt(plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null);
        g.setUpdatedAt(plan.getUpdatedAt() != null ? plan.getUpdatedAt().toString() : null);
        return g;
    }

    private Plan toPoco(GraphPlan g) {
        Plan plan = new Plan();
        plan.setId(g.getId());
        plan.setWorkspaceId(g.getWorkspaceId());
        plan.setName(g.getName());
        plan.setParentId(g.getParentId());
        plan.setSchemaId(g.getSchemaId());
        if (g.getLevel() != null) plan.setLevel(PlanLevel.valueOf(g.getLevel()));
        try {
            String tasksJson = g.getTasksJson();
            if (tasksJson != null && !tasksJson.isBlank()) {
                plan.setTasks(mapper.readValue(tasksJson,
                        mapper.getTypeFactory().constructCollectionType(List.class, Task.class)));
            }
        } catch (Exception e) {
            log.error("Error deserializing tasks: {}", e.getMessage());
        }
        if (g.getCreatedAt() != null) plan.setCreatedAt(Instant.parse(g.getCreatedAt()));
        if (g.getUpdatedAt() != null) plan.setUpdatedAt(Instant.parse(g.getUpdatedAt()));
        return plan;
    }
}
