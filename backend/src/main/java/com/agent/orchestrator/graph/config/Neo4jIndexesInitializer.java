package com.agent.orchestrator.graph.config;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Configuration
public class Neo4jIndexesInitializer {

    private static final Logger log = LoggerFactory.getLogger(Neo4jIndexesInitializer.class);

    private final Driver driver;

    public Neo4jIndexesInitializer(Driver driver) {
        this.driver = driver;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createIndexes() {
        try (var session = driver.session()) {
            session.run("CREATE INDEX class_hash_idx IF NOT EXISTS FOR (c:Class) ON (c.hash)");
            session.run("CREATE INDEX class_qualified_name_idx IF NOT EXISTS FOR (c:Class) ON (c.qualifiedName)");
            session.run("CREATE INDEX method_hash_idx IF NOT EXISTS FOR (m:Method) ON (m.hash)");
            session.run("CREATE INDEX method_signature_idx IF NOT EXISTS FOR (m:Method) ON (m.signature)");
            session.run("CREATE INDEX method_name_idx IF NOT EXISTS FOR (m:Method) ON (m.name)");
            session.run("CREATE INDEX class_name_idx IF NOT EXISTS FOR (c:Class) ON (c.name)");
            session.run("CREATE INDEX exec_run_schema_id_idx IF NOT EXISTS FOR (r:ExecutionRun) ON (r.schemaId)");
            session.run("CREATE INDEX exec_run_status_idx IF NOT EXISTS FOR (r:ExecutionRun) ON (r.status)");
            session.run("CREATE INDEX node_exec_run_id_idx IF NOT EXISTS FOR (n:NodeExecution) ON (n.runId)");
            session.run("CREATE INDEX checkpoint_run_id_idx IF NOT EXISTS FOR (c:Checkpoint) ON (c.runId)");
            session.run("CREATE INDEX exec_record_schema_id_idx IF NOT EXISTS FOR (r:ExecutionRecord) ON (r.schemaId)");
            session.run("CREATE INDEX exec_record_start_time_idx IF NOT EXISTS FOR (r:ExecutionRecord) ON (r.startTime)");
            session.run("CREATE INDEX plan_workspace_id_idx IF NOT EXISTS FOR (p:Plan) ON (p.workspaceId)");
            session.run("CREATE INDEX schema_user_id_idx IF NOT EXISTS FOR (s:WorkflowSchema) ON (s.userId)");
            session.run("CREATE INDEX plan_step_schema_id_idx IF NOT EXISTS FOR (p:PlanStep) ON (p.schemaId)");

            log.info("Neo4j indexes created successfully");
        } catch (Exception e) {
            log.warn("Failed to create indexes (may already exist): {}", e.getMessage(), e);
        }
    }
}