package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.*;

@Service
@Transactional
public class SchemaServiceImpl implements SchemaService {

    private static final Logger log = LoggerFactory.getLogger(SchemaServiceImpl.class);

    private final Neo4jSchemaRepository schemaRepository;
    private final MemPalaceClient memPalaceClient;
    private final SettingsService settingsService;
    private final SchemaExporter schemaExporter;
    private final LlmService llmService;
    private final PlanService planService;
    private final ExecutionRepository executionRepository;
    private final SchemaExecutionService executionService;

    public SchemaServiceImpl(Neo4jSchemaRepository schemaRepository,
                             MemPalaceClient memPalaceClient,
                             SettingsService settingsService,
                             SchemaExporter schemaExporter,
                             LlmService llmService,
                             PlanService planService,
                             ExecutionRepository executionRepository,
                             SchemaExecutionService executionService) {
        this.schemaRepository = schemaRepository;
        this.memPalaceClient = memPalaceClient;
        this.settingsService = settingsService;
        this.schemaExporter = schemaExporter;
        this.llmService = llmService;
        this.planService = planService;
        this.executionRepository = executionRepository;
        this.executionService = executionService;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        initDemoSchema();
    }

    // ────────────────────────── CRUD ──────────────────────────

    @Override
    public List<WorkflowSchema> getAllSchemas() {
        return schemaRepository.findAll();
    }

    @Override
    public List<WorkflowSchema> getSchemasByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            log.warn("getSchemasByUserId called with null/blank userId");
            return List.of();
        }
        return schemaRepository.findByUserId(userId);
    }

    @Override
    public Map<String, List<WorkflowSchema>> getSchemasGrouped(String userId) {
        List<WorkflowSchema> schemas = schemaRepository.findByUserId(userId);
        if (schemas == null || schemas.isEmpty()) return Map.of();

        return schemas.stream()
                .filter(s -> s.getName() != null && !s.getName().isBlank())
                .collect(Collectors.groupingBy(this::resolveGroupKey,
                        TreeMap::new,
                        Collectors.toList()));
    }

    private String resolveGroupKey(WorkflowSchema s) {
        String name = s.getName().trim();
        if (name.startsWith("pw-") || name.startsWith("pw_")) return "PlayWright";
        int dash = name.indexOf('-');
        if (dash > 0 && dash < name.length() - 1) {
            return name.substring(0, dash).trim();
        }
        return "Other";
    }

    @Override
    public List<WorkflowSchema> getRecentSchemas(String userId, int limit) {
        List<WorkflowSchema> all = schemaRepository.findByUserId(userId);
        if (all == null || all.isEmpty()) return List.of();
        return all.stream()
                .filter(s -> s.getUpdatedAt() != null)
                .sorted(Comparator.comparing(WorkflowSchema::getUpdatedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public WorkflowSchema getSchema(String id) {
        return schemaRepository.findById(id);
    }

    @Override
    public WorkflowSchema createSchema(WorkflowSchema schema) {
        if (schema.getId() == null) {
            schema.setId(UUID.randomUUID().toString());
        }
        if (schema.getCreatedAt() == null) {
            schema.setCreatedAt(Instant.now());
        }
        if (schema.getUpdatedAt() == null) {
            schema.setUpdatedAt(Instant.now());
        }
        // Default model: try user default, then global default
        if (schema.getDefaultModel() == null || schema.getDefaultModel().isBlank()) {
            String userModel = settingsService.getUserDefaultModel(schema.getUserId());
            if (userModel != null && !userModel.isBlank()) {
                schema.setDefaultModel(userModel);
            } else {
                schema.setDefaultModel(settingsService.getGlobalDefaultModel());
            }
        }
        log.info("Creating schema: id={}, name={}, userId={}", schema.getId(), schema.getName(), schema.getUserId());
        schemaRepository.save(schema);
        return schema;
    }

    @Override
    public WorkflowSchema updateSchema(String id, WorkflowSchema incoming) {
        WorkflowSchema existing = schemaRepository.findById(id);
        if (existing == null) {
            log.warn("Schema {} not found for update, creating via upsert", id);
            if (incoming.getId() == null) incoming.setId(id);
            incoming.setUpdatedAt(Instant.now());
            schemaRepository.save(incoming);
            return incoming;
        }

        // Merge fields
        if (incoming.getName() != null) existing.setName(incoming.getName());
        if (incoming.getDescription() != null) existing.setDescription(incoming.getDescription());
        if (incoming.getNodes() != null) existing.setNodes(incoming.getNodes());
        if (incoming.getEdges() != null) existing.setEdges(incoming.getEdges());
        if (incoming.getDefaultModel() != null) existing.setDefaultModel(incoming.getDefaultModel());
        if (incoming.getUserId() != null) existing.setUserId(incoming.getUserId());
        if (incoming.getPipeline() != null) existing.setPipeline(incoming.getPipeline());
        if (incoming.getVersion() != null) existing.setVersion(incoming.getVersion());
        if (incoming.getTargetPath() != null) existing.setTargetPath(incoming.getTargetPath());
        if (incoming.getProjectType() != null) existing.setProjectType(incoming.getProjectType());
        existing.setUpdatedAt(Instant.now());
        schemaRepository.save(existing);
        return existing;
    }

    @Override
    public void deleteSchema(String id) {
        schemaRepository.delete(id);
    }

    @Override
    public void batchDeleteSchemas(List<String> ids) {
        for (String id : ids) {
            schemaRepository.delete(id);
        }
    }

    @Override
    public void updateLastRunAt(String schemaId) {
        WorkflowSchema s = schemaRepository.findById(schemaId);
        if (s != null) {
            s.setLastRunAt(Instant.now());
            schemaRepository.save(s);
        }
    }

    // ── Execution (delegated) ──

    @Override
    public void executeSchema(String id) {
        executionService.executeSchema(id);
    }

    @Override
    public void executeSchema(String id, String sessionInput) {
        executionService.executeSchema(id, sessionInput);
    }

    @Override
    public void cancelExecution(String id) {
        executionService.cancelExecution(id);
    }

    @Override
    public void resumeExecution(String schemaId) {
        executionService.resumeExecution(schemaId);
    }

    @Override
    public void resumeExecution(String schemaId, WorkflowSchema schema) {
        executionService.resumeExecution(schemaId, schema);
    }

    @Override
    public void resumeExecution(String schemaId, WorkflowSchema schema, String parentRunId) {
        executionService.resumeExecution(schemaId, schema, parentRunId);
    }

    @Override
    public void resumeExecution(String schemaId, String runId) {
        executionService.resumeExecution(schemaId, runId);
    }

    // ── Execution runs ──

    @Override
    public List<ExecutionRun> findExecutionRuns(String schemaId) {
        return executionService.findExecutionRuns(schemaId);
    }

    @Override
    public ExecutionRun getPausedRun(String schemaId) {
        return executionService.getPausedRun(schemaId);
    }

    @Override
    public List<NodeExecution> getExecutionNodes(String runId) {
        return executionService.getExecutionNodes(runId);
    }

    // ── History ──

    @Override
    public List<ExecutionRecord> getExecutionHistory(String schemaId) {
        return executionService.getExecutionHistory(schemaId);
    }

    @Override
    public List<ExecutionRecord> getAllExecutionHistory() {
        return executionService.getAllExecutionHistory();
    }

    @Override
    public String getOutputFileContent(String schemaId, String nodeId) {
        return executionService.getOutputFileContent(schemaId, nodeId);
    }

    // ── Results ──

    @Override
    public Map<String, String> getExecutionResults(String executionId) {
        return executionService.getExecutionResults(executionId);
    }

    @Override
    public Map<String, Object> getGeneratedFiles(String schemaId) {
        return executionService.getGeneratedFiles(schemaId);
    }

    // ── Config hash ──

    @Override
    public String computeConfigHash(Node node, WorkflowSchema schema) {
        return executionService.computeConfigHash(node, schema);
    }

    // ── Review handling (delegated) ──

    @Override
    public void handleReviewFeedback(String executionId, String nodeId, String feedback, List<Map<String, Object>> history) {
        executionService.handleReviewFeedback(executionId, nodeId, feedback, history);
    }

    @Override
    public void handleReviewApprove(String executionId, String nodeId) {
        executionService.handleReviewApprove(executionId, nodeId);
    }

    @Override
    public void handleReviewApprove(String executionId, String nodeId, String schemaId) {
        executionService.handleReviewApprove(executionId, nodeId, schemaId);
    }

    @Override
    public void handleReviewReject(String executionId, String nodeId) {
        executionService.handleReviewReject(executionId, nodeId);
    }

    @Override
    public void handleReviewReject(String executionId, String nodeId, String schemaId) {
        executionService.handleReviewReject(executionId, nodeId, schemaId);
    }

    @Override
    public void handleDiffsApprove(String executionId, String nodeId) {
        executionService.handleDiffsApprove(executionId, nodeId);
    }

    @Override
    public void handleDiffsReject(String executionId, String nodeId) {
        executionService.handleDiffsReject(executionId, nodeId);
    }

    // ── Export / Import ──

    @Override
    public WorkflowSchema exportSchema(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Schema ID is required");
        }
        return schemaRepository.findById(id);
    }

    @Override
    public String exportToMermaid(String id) {
        return schemaExporter.exportToMermaid(id);
    }

    @Override
    public String exportToPython(String id) {
        return schemaExporter.exportToPython(id);
    }

    @Override
    public WorkflowSchema importSchema(WorkflowSchema schema, String userId) {
        if (schema.getId() == null) {
            schema.setId(UUID.randomUUID().toString());
        }
        schema.setUserId(userId);
        schema.setCreatedAt(Instant.now());
        schema.setUpdatedAt(Instant.now());
        log.info("Importing schema: id={}, name={}", schema.getId(), schema.getName());
        schemaRepository.save(schema);
        return schema;
    }

    // ── Validation ──

    @Override
    public SchemaValidationResult validateSchema(String id) {
        WorkflowSchema schema = schemaRepository.findById(id);
        if (schema == null) {
            SchemaValidationResult result = new SchemaValidationResult();
            result.addError("schema", "Schema not found: " + id);
            return result;
        }
        return new SchemaValidator().validate(schema);
    }

    // ── Demo ──

    private void initDemoSchema() {
        if (schemaRepository.findById("demo-1") != null) return;

        WorkflowSchema demo = new WorkflowSchema();
        demo.setId("demo-1");
        demo.setName("Demo: Data Analysis");
        demo.setDescription("Simple schema: source -> agent -> output");
        demo.setVersion("1.0");

        List<Node> nodes = new ArrayList<>();

        Node source = new Node();
        source.setId("source-1");
        source.setType("source");
        source.setName("Source");
        Node.Position pos1 = new Node.Position();
        pos1.setX(100);
        pos1.setY(200);
        source.setPosition(pos1);
        nodes.add(source);

        Node agent = new Node();
        agent.setId("agent-1");
        agent.setType("agent");
        agent.setName("AI Analyst");
        Node.Position pos2 = new Node.Position();
        pos2.setX(400);
        pos2.setY(200);
        agent.setPosition(pos2);
        Node.NodeData data = new Node.NodeData();
        data.setUserPrompt("You are an experienced analyst. Analyze the provided data and draw conclusions.");
        data.setModel("local");
        agent.setData(data);
        nodes.add(agent);

        Node output = new Node();
        output.setId("output-1");
        output.setType("output");
        output.setName("Result");
        Node.Position pos3 = new Node.Position();
        pos3.setX(700);
        pos3.setY(200);
        output.setPosition(pos3);
        nodes.add(output);

        demo.setNodes(nodes);

        List<Edge> edges = new ArrayList<>();

        Edge edge1 = new Edge();
        edge1.setId("edge-1");
        edge1.setSource("source-1");
        edge1.setTarget("agent-1");
        edge1.setType("data");
        edges.add(edge1);

        Edge edge2 = new Edge();
        edge2.setId("edge-2");
        edge2.setSource("agent-1");
        edge2.setTarget("output-1");
        edge2.setType("data");
        edges.add(edge2);

        demo.setEdges(edges);
        schemaRepository.save(demo);
        log.info("Demo schema added: {}", demo.getName());
    }

    // ── Prompt constants ──

    public static final String ARCHITECT_ANALYST_PROMPT = SchemaExecutionService.ARCHITECT_ANALYST_PROMPT;
    public static final String FEATURE_DESIGNER_PROMPT = SchemaExecutionService.FEATURE_DESIGNER_PROMPT;
    public static final String TASK_BREAKDOWN_PROMPT = SchemaExecutionService.TASK_BREAKDOWN_PROMPT;
    public static final String PLANNING_WORKFLOW_USER_PROMPT = SchemaExecutionService.PLANNING_WORKFLOW_USER_PROMPT;

    // ── Test accessors ──

    List<List<Node>> getExecutionLevelsPublic(WorkflowSchema schema) { return executionService.getExecutionLevelsPublic(schema); }
    Set<String> computeSkippedNodesPublic(WorkflowSchema schema) { return executionService.computeSkippedNodesPublic(schema); }

    public static boolean isTestSchema(WorkflowSchema schema) {
        if (schema == null || schema.getName() == null) return false;
        String name = schema.getName().toLowerCase();
        return name.startsWith("test-") || name.startsWith("pw-review") || name.startsWith("debug-")
            || name.startsWith("check-") || name.startsWith("premortem-") || name.startsWith("pipeline-")
            || name.startsWith("final-") || name.startsWith("valid-")
            || name.contains("-debug") || name.contains("-test");
    }

    private WorkflowSchema sanitizeSchema(WorkflowSchema schema) {
        if (schema == null) return null;
        if (schema.getNodes() != null) {
            schema.setNodes(schema.getNodes().stream().filter(n -> n != null && n.getId() != null).toList());
        }
        if (schema.getEdges() != null) {
            schema.setEdges(schema.getEdges().stream().filter(e -> e != null && e.getId() != null).toList());
        }
        return schema;
    }

    private String resolveModel(String nodeModel, WorkflowSchema schema) {
        if (nodeModel != null && !nodeModel.isBlank()) {
            log.debug("resolveModel: using nodeModel='{}'", nodeModel);
            return nodeModel;
        }
        if (schema.getDefaultModel() != null && !schema.getDefaultModel().isBlank()) {
            log.debug("resolveModel: using schema.defaultModel='{}'", schema.getDefaultModel());
            return schema.getDefaultModel();
        }
        if (schema.getUserId() != null) {
            String userModel = settingsService.getUserDefaultModel(schema.getUserId());
            log.debug("resolveModel: schema.userId='{}', userModel='{}'", schema.getUserId(), userModel);
            if (userModel != null && !userModel.isBlank()) return userModel;
        } else {
            log.debug("resolveModel: schema.userId is NULL");
        }
        String global = settingsService.getGlobalDefaultModel();
        log.debug("resolveModel: global='{}'", global);
        if (global != null && !global.isBlank()) return global;
        log.warn("resolveModel: all fallbacks exhausted, using hardcoded default");
        return "deepseek-v4-flash-free";
    }

    @Override
    public Map<String, Object> generateSchemaFromPrompt(String prompt, String model) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", "generateSchemaFromPrompt has been removed. Use Quick Start fixed pipeline.");
        return result;
    }
}
