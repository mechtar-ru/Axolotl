package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.model.WorkflowSchema;
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
public class Neo4jSchemaRepository {
    private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaRepository.class);
    private final Driver driver;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public Neo4jSchemaRepository(Driver driver) {
        this.driver = driver;
    }

    public List<WorkflowSchema> findAll() {
        List<WorkflowSchema> result = new ArrayList<>();
        try (Session session = driver.session()) {
            Result rs = session.run("MATCH (s:WorkflowSchema) RETURN s.id, s.name, s.data, s.userId, s.createdAt, s.updatedAt");
            while (rs.hasNext()) {
                var record = rs.next();
                WorkflowSchema schema = mapper.readValue(record.get("s.data").asString(), WorkflowSchema.class);
                result.add(schema);
            }
        } catch (Exception e) {
            log.error("Error loading schemas: {}", e.getMessage());
        }
        return result;
    }

    public List<WorkflowSchema> findByUserId(String userId) {
        List<WorkflowSchema> result = new ArrayList<>();
        try (Session session = driver.session()) {
            Result rs = session.run("MATCH (s:WorkflowSchema {userId: $userId}) RETURN s.data", 
                org.neo4j.driver.Values.parameters("userId", userId));
            while (rs.hasNext()) {
                var record = rs.next();
                WorkflowSchema schema = mapper.readValue(record.get("s.data").asString(), WorkflowSchema.class);
                result.add(schema);
            }
        } catch (Exception e) {
            log.error("Error loading schemas by user: {}", e.getMessage());
        }
        return result;
    }

    public WorkflowSchema findById(String id) {
        try (Session session = driver.session()) {
            Result rs = session.run("MATCH (s:WorkflowSchema {id: $id}) RETURN s.data", 
                org.neo4j.driver.Values.parameters("id", id));
            if (rs.hasNext()) {
                return mapper.readValue(rs.next().get("s.data").asString(), WorkflowSchema.class);
            }
        } catch (Exception e) {
            log.error("Error loading schema: {}", e.getMessage());
        }
        return null;
    }

    public void save(WorkflowSchema schema) {
        try (Session session = driver.session()) {
            String json = mapper.writeValueAsString(schema);
            session.run("""
                MERGE (s:WorkflowSchema {id: $id})
                SET s.name = $name, s.data = $data, s.userId = $userId, s.workspaceId = $workspaceId, s.createdAt = $createdAt, s.updatedAt = $updatedAt
                """, 
                org.neo4j.driver.Values.parameters(
                    "id", schema.getId(),
                    "name", schema.getName() != null ? schema.getName() : "",
                    "data", json,
                    "userId", schema.getUserId() != null ? schema.getUserId() : "default",
                    "workspaceId", schema.getWorkspaceId() != null ? schema.getWorkspaceId() : "default",
                    "createdAt", schema.getCreatedAt() != null ? schema.getCreatedAt() : "",
                    "updatedAt", schema.getUpdatedAt() != null ? schema.getUpdatedAt() : ""
                ));
        } catch (Exception e) {
            log.error("Error saving schema: {}", e.getMessage());
        }
    }

    public void delete(String id) {
        try (Session session = driver.session()) {
            session.run("MATCH (s:WorkflowSchema {id: $id}) DETACH DELETE s", 
                org.neo4j.driver.Values.parameters("id", id));
        } catch (Exception e) {
            log.error("Error deleting schema: {}", e.getMessage());
        }
    }

    public void deleteAll() {
        try (Session session = driver.session()) {
            session.run("MATCH (s:WorkflowSchema) DETACH DELETE s");
        }
    }

    public long count() {
        try (Session session = driver.session()) {
            Result rs = session.run("MATCH (s:WorkflowSchema) RETURN count(s) as count");
            if (rs.hasNext()) {
                return rs.next().get("count").asLong();
            }
        }
        return 0;
    }
}