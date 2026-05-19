package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.model.Plan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class Neo4jPlanRepository {
    private static final Logger log = LoggerFactory.getLogger(Neo4jPlanRepository.class);
    private final Driver driver;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public Neo4jPlanRepository(Driver driver) {
        this.driver = driver;
    }

    public List<Plan> findByWorkspaceId(String workspaceId) {
        List<Plan> result = new ArrayList<>();
        try (Session session = driver.session()) {
            Result rs = session.run("MATCH (p:Plan {workspaceId: $workspaceId}) RETURN p.data", 
                org.neo4j.driver.Values.parameters("workspaceId", workspaceId));
            while (rs.hasNext()) {
                var record = rs.next();
                Plan plan = mapper.readValue(record.get("p.data").asString(), Plan.class);
                result.add(plan);
            }
        } catch (Exception e) {
            log.error("Error loading plans: {}", e.getMessage());
        }
        return result;
    }

    public Plan findFirstByWorkspaceId(String workspaceId) {
        try (Session session = driver.session()) {
            Result rs = session.run("""
                MATCH (p:Plan {workspaceId: $workspaceId})
                RETURN p.data, p.tasksJson
                ORDER BY 
                    CASE WHEN p.tasksJson IS NOT NULL THEN 2 
                         WHEN p.data IS NOT NULL AND size(p.data) > 500 THEN 1 
                         ELSE 0 END DESC
                LIMIT 1
                """,
                org.neo4j.driver.Values.parameters("workspaceId", workspaceId));
            if (rs.hasNext()) {
                var record = rs.next();
                String json = record.get("p.tasksJson").isNull() 
                    ? record.get("p.data").asString() 
                    : "{\"tasks\":" + record.get("p.tasksJson").asString() + "}";
                if (json != null && !json.isBlank()) {
                    json = json.trim();
                    if (json.startsWith("[")) {
                        return null;
                    }
                    return mapper.readValue(json, Plan.class);
                }
            }
        } catch (Exception e) {
            log.error("Error loading plan: {}", e.getMessage());
        }
        return null;
    }

    public Plan findById(String id) {
        try (Session session = driver.session()) {
            Result rs = session.run("MATCH (p:Plan {id: $id}) RETURN p.data", 
                org.neo4j.driver.Values.parameters("id", id));
            if (rs.hasNext()) {
                return mapper.readValue(rs.next().get("p.data").asString(), Plan.class);
            }
        } catch (Exception e) {
            log.error("Error loading plan: {}", e.getMessage());
        }
        return null;
    }

    public void save(Plan plan) {
        try (Session session = driver.session()) {
            String json = mapper.writeValueAsString(plan);
            String createdAt = plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : "";
            String updatedAt = plan.getUpdatedAt() != null ? plan.getUpdatedAt().toString() : "";
            session.run("""
                MERGE (p:Plan {id: $id})
                SET p.workspaceId = $workspaceId, p.name = $name, p.data = $data, p.createdAt = $createdAt, p.updatedAt = $updatedAt
                """, 
                org.neo4j.driver.Values.parameters(
                    "id", plan.getId(),
                    "workspaceId", plan.getWorkspaceId() != null ? plan.getWorkspaceId() : "default",
                    "name", plan.getName() != null ? plan.getName() : "",
                    "data", json,
                    "createdAt", createdAt,
                    "updatedAt", updatedAt
                ));
        } catch (Exception e) {
            log.error("Error saving plan: {}", e.getMessage());
        }
    }

    public void delete(String id) {
        try (Session session = driver.session()) {
            session.run("MATCH (p:Plan {id: $id}) DETACH DELETE p", 
                org.neo4j.driver.Values.parameters("id", id));
        } catch (Exception e) {
            log.error("Error deleting plan: {}", e.getMessage());
        }
    }

    public long count() {
        try (Session session = driver.session()) {
            Result rs = session.run("MATCH (p:Plan) RETURN count(p) as count");
            if (rs.hasNext()) {
                return rs.next().get("count").asLong();
            }
        }
        return 0;
    }

    public List<String> findAllWorkspaceIds() {
        List<String> result = new ArrayList<>();
        try (Session session = driver.session()) {
            Result rs = session.run("MATCH (p:Plan) RETURN DISTINCT p.workspaceId as ws");
            while (rs.hasNext()) {
                var ws = rs.next().get("ws");
                if (!ws.isNull()) result.add(ws.asString());
            }
        }
        return result;
    }

    public List<Plan> findByParentId(String parentId) {
        List<Plan> result = new ArrayList<>();
        try (Session session = driver.session()) {
            // Use Jackson to properly escape parentId for JSON substring search
            String searchFragment;
            try {
                searchFragment = "\"parentId\":" + mapper.writeValueAsString(parentId);
            } catch (Exception e) {
                searchFragment = "\"parentId\":\"" + parentId + "\"";
            }
            Result rs = session.run("MATCH (p:Plan) WHERE p.data CONTAINS $parentId RETURN p.data",
                org.neo4j.driver.Values.parameters("parentId", searchFragment));
            while (rs.hasNext()) {
                try {
                    Plan plan = mapper.readValue(rs.next().get("p.data").asString(), Plan.class);
                    if (parentId.equals(plan.getParentId())) result.add(plan);
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    public List<Plan> findBySchemaId(String schemaId) {
        List<Plan> result = new ArrayList<>();
        try (Session session = driver.session()) {
            String searchFragment;
            try {
                searchFragment = "\"schemaId\":" + mapper.writeValueAsString(schemaId);
            } catch (Exception e) {
                searchFragment = "\"schemaId\":\"" + schemaId + "\"";
            }
            Result rs = session.run("MATCH (p:Plan) WHERE p.data CONTAINS $schemaId RETURN p.data",
                org.neo4j.driver.Values.parameters("schemaId", searchFragment));
            while (rs.hasNext()) {
                try {
                    Plan plan = mapper.readValue(rs.next().get("p.data").asString(), Plan.class);
                    if (schemaId.equals(plan.getSchemaId())) result.add(plan);
                } catch (Exception ignored) {}
            }
        }
        return result;
    }
}