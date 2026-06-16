package com.agent.orchestrator.graph.repository;

import com.agent.orchestrator.model.WorkflowSchema;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public Neo4jSchemaRepository(Driver driver) {
        this.driver = driver;
    }

    public List<WorkflowSchema> findAll() {
        List<WorkflowSchema> result = new ArrayList<>();
        try (Session session = driver.session()) {
            // Read only from the JSON blob (single source of truth)
            Result rs = session.run("MATCH (s:WorkflowSchema) RETURN s.data");
            int count = 0;
            while (rs.hasNext()) {
                var record = rs.next();
                WorkflowSchema schema = mapper.readValue(record.get("s.data").asString(), WorkflowSchema.class);
                result.add(schema);
                count++;
            }
            log.info("Neo4j query returned {} records", count);
        } catch (Exception e) {
            log.error("Error loading schemas: {}", e.getMessage(), e);
        }
        log.info("Returning {} schemas from Neo4j", result.size());
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
                SET s.data = $data,
                    s.name = $name,
                    s.userId = $userId,
                    s.workspaceId = $workspaceId,
                    s.createdAt = $createdAt,
                    s.updatedAt = $updatedAt,
                    s.lastRunAt = $lastRunAt
                """,
                org.neo4j.driver.Values.parameters(
                    "id", schema.getId(),
                    "data", json,
                    "name", schema.getName(),
                    "userId", schema.getUserId(),
                    "workspaceId", schema.getWorkspaceId(),
                    "createdAt", schema.getCreatedAt() != null ? schema.getCreatedAt().toString() : null,
                    "updatedAt", schema.getUpdatedAt() != null ? schema.getUpdatedAt().toString() : null,
                    "lastRunAt", schema.getLastRunAt() != null ? schema.getLastRunAt().toString() : null
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

    /**
     * Check consistency between JSON blob data and node-level properties.
     * Logs warnings for any discrepancies found.
     * @return number of schemas with inconsistencies
     */
    public int consistencyCheck() {
        int inconsistencies = 0;
        try (Session session = driver.session()) {
            Result rs = session.run("""
                MATCH (s:WorkflowSchema)
                RETURN s.id as id, s.name as name, s.userId as userId,
                       s.workspaceId as workspaceId, s.createdAt as createdAt,
                       s.updatedAt as updatedAt, s.data as data
                """);
            while (rs.hasNext()) {
                var record = rs.next();
                String id = record.get("id").asString();
                String dataJson = record.get("data").asString();
                String nodeName = record.get("name").isNull() ? null : record.get("name").asString();
                String nodeUserId = record.get("userId").isNull() ? null : record.get("userId").asString();
                String nodeWorkspaceId = record.get("workspaceId").isNull() ? null : record.get("workspaceId").asString();
                String nodeCreatedAt = record.get("createdAt").isNull() ? null : record.get("createdAt").asString();
                String nodeUpdatedAt = record.get("updatedAt").isNull() ? null : record.get("updatedAt").asString();

                try {
                    WorkflowSchema schema = mapper.readValue(dataJson, WorkflowSchema.class);
                    
                    if (nodeName != null && !nodeName.equals(schema.getName())) {
                        log.warn("Inconsistency [{}]: node name='{}' vs blob name='{}'", id, nodeName, schema.getName());
                        inconsistencies++;
                    }
                    if (nodeUserId != null && !nodeUserId.equals(schema.getUserId())) {
                        log.warn("Inconsistency [{}]: node userId='{}' vs blob userId='{}'", id, nodeUserId, schema.getUserId());
                        inconsistencies++;
                    }
                    if (nodeWorkspaceId != null && !nodeWorkspaceId.equals(schema.getWorkspaceId())) {
                        log.warn("Inconsistency [{}]: node workspaceId='{}' vs blob workspaceId='{}'", id, nodeWorkspaceId, schema.getWorkspaceId());
                        inconsistencies++;
                    }
                    if (nodeCreatedAt != null && !nodeCreatedAt.equals(schema.getCreatedAt())) {
                        log.warn("Inconsistency [{}]: node createdAt='{}' vs blob createdAt='{}'", id, nodeCreatedAt, schema.getCreatedAt());
                        inconsistencies++;
                    }
                    if (nodeUpdatedAt != null && !nodeUpdatedAt.equals(schema.getUpdatedAt())) {
                        log.warn("Inconsistency [{}]: node updatedAt='{}' vs blob updatedAt='{}'", id, nodeUpdatedAt, schema.getUpdatedAt());
                        inconsistencies++;
                    }
                } catch (Exception e) {
                    log.warn("Cannot parse data blob for schema {}: {}", id, e.getMessage());
                    inconsistencies++;
                }
            }
        } catch (Exception e) {
            log.error("Error during consistency check: {}", e.getMessage(), e);
        }
        if (inconsistencies > 0) {
            log.warn("Consistency check complete: {} schemas with inconsistencies", inconsistencies);
        } else {
            log.info("Consistency check complete: all schemas are consistent");
        }
        return inconsistencies;
    }
}