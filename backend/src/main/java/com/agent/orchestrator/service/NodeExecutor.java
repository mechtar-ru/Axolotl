package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeExecutor.class);

    private final ExecutionUtilityService utilityService;
    private final OutputReportingService outputReportingService;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final ToolExecutor toolExecutor;
    private final TransformService transformService;
    private final Neo4jSchemaRepository schemaRepository;
    private final PlanService planService;
    private final ProjectContextBuilder projectContextBuilder;
    private final ExecutionRepository executionRepository;
    private final ExecutionStateManager stateManager;
    private final List<NodeExecutionStrategy> strategies;

    @Value("${axolotl.sandbox.allowedWriteDirs:.}")
    private java.util.List<String> allowedWriteDirs;

    private static final int MAX_CONTEXT_CHARS = 4000;
    private static final int MAX_SUBAGENT_DEPTH = 5;

    private volatile Map<String, NodeExecutionStrategy> strategyMap;

    public NodeExecutor(ExecutionUtilityService utilityService,
                        OutputReportingService outputReportingService,
                        LlmService llmService,
                        ExecutionWebSocketHandler webSocketHandler,
                        MemPalaceClient memPalaceClient,
                        ToolExecutor toolExecutor,
                        TransformService transformService,
                        Neo4jSchemaRepository schemaRepository,
                        PlanService planService,
                        ProjectContextBuilder projectContextBuilder,
                        ExecutionRepository executionRepository,
                        ExecutionStateManager stateManager,
                        List<NodeExecutionStrategy> strategies) {
        this.utilityService = utilityService;
        this.outputReportingService = outputReportingService;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.toolExecutor = toolExecutor;
        this.transformService = transformService;
        this.schemaRepository = schemaRepository;
        this.planService = planService;
        this.projectContextBuilder = projectContextBuilder;
        this.executionRepository = executionRepository;
        this.stateManager = stateManager;
        this.strategies = strategies;
    }

    public static final String ARCHITECT_ANALYST_PROMPT = """
            You are a senior software architect analyzing a project's codebase structure.
            Given the project's file tree, key configuration files, and source code excerpts, provide:

            1. **Architecture Overview**: What patterns and frameworks are used? How is the code organized?
            2. **Technology Stack**: Languages, frameworks, build tools, databases.
            3. **Module Breakdown**: List each major module/directory and its responsibility.
            4. **Extension Points**: Where can new features be plugged in? What interfaces/hooks exist?
            5. **Constraints**: Any architectural decisions, conventions, or limitations to be aware of.

            Be specific — reference actual file paths and class names. Output in structured markdown.
            """;

    public static final String FEATURE_DESIGNER_PROMPT = """
            You are a feature design architect. Given:
            - A project's architecture analysis
            - A list of requested features/improvements

            For each feature, design:
            1. **Scope**: Which files/modules need changes
            2. **Approach**: Step-by-step implementation plan
            3. **Dependencies**: What must be built first
            4. **Risks**: Potential breaking changes or conflicts
            5. **Estimate**: Relative complexity (S/M/L/XL)

            Output structured markdown with clear sections per feature.
            Prioritize features by dependency order.
            """;

    public static final String TASK_BREAKDOWN_PROMPT = """
            You are a project planner breaking down feature designs into actionable development tasks.

            Given feature designs and architecture context, output a structured task list as JSON:
            {
              "tasks": [
                {
                  "title": "Short task title",
                  "description": "What to do, which files to change",
                  "priority": "HIGH|MEDIUM|LOW",
                  "dependencies": ["task title that must be done first"],
                  "acceptanceCriteria": ["testable criterion 1", "criterion 2"],
                  "order": 1
                }
              ]
            }

            Rules:
            - Each task should be completable in 1-4 hours
            - Include specific file paths and function names
            - Every task must have at least 2 acceptance criteria
            - Order tasks by dependency chain
            - Group related tasks together
            """;

    public static final String PLANNING_WORKFLOW_USER_PROMPT = """
            Analyze this project and design a plan for implementing the following features:

            {{features}}

            Use the project context provided by the previous node to create a detailed implementation plan.
            """;

    @PostConstruct
    void initStrategyMap() {
        this.strategyMap = new HashMap<>();
        for (NodeExecutionStrategy s : strategies) {
            strategyMap.put(s.supportedNodeType(), s);
        }
        this.agentStrategy = (AgentNodeStrategy) strategyFor("agent");
        this.schemaBuilderStrategy = (SchemaBuilderNodeStrategy) strategyFor("schemabuilder");
        this.verifierStrategy = (VerifierNodeStrategy) strategyFor("verifier");
        this.reviewStrategy = (ReviewNodeStrategy) strategyFor("review");
        this.draftStrategy = (DraftNodeStrategy) strategyFor("draft");
    }

    private NodeExecutionStrategy strategyFor(String nodeType) {
        NodeExecutionStrategy s = strategyMap.get(nodeType);
        if (s == null) {
            throw new IllegalArgumentException("No strategy found for node type: " + nodeType);
        }
        return s;
    }

    private AgentNodeStrategy agentStrategy;
    private SchemaBuilderNodeStrategy schemaBuilderStrategy;
    private VerifierNodeStrategy verifierStrategy;
    private ReviewNodeStrategy reviewStrategy;
    private DraftNodeStrategy draftStrategy;

    // ────────────────────────── result maps (delegated to stateManager) ──────────────────────────

    public Map<String, Map<String, String>> getNodeResults() {
        return stateManager.getNodeResults();
    }

    public Map<String, String> getConditionResults() {
        return stateManager.getConditionResults();
    }

    public Map<String, String> getOutputFileRegistry() {
        return stateManager.getOutputFileRegistry();
    }

    public Map<String, Object> getGeneratedFilesRegistry() {
        return stateManager.getGeneratedFilesRegistry();
    }

    public void setCurrentRunId(String schemaId, String runId) {
        stateManager.setCurrentRunId(schemaId, runId);
    }

    // ────────────────────────── agent nodes (delegated to AgentNodeStrategy) ──────────────────────────

    public String executeAgentNode(Node node, String schemaId, String resolvedModel) {
        return agentStrategy.executeAgentNode(node, schemaId, resolvedModel, null);
    }

    public String executeToolAgentNode(Node node, String schemaId, String resolvedModel) {
        return agentStrategy.executeToolAgentNode(node, schemaId, resolvedModel, null);
    }

    public String simulateAgentNode(Node node, String schemaId) {
        return agentStrategy.simulateAgentNode(node, schemaId);
    }

    public String analyzeAgentNode(Node node, String schemaId) {
        return agentStrategy.analyzeAgentNode(node, schemaId);
    }

    // ────────────────────────── output / command / filewrite (delegated) ──────────────────────────

    public String executeOutputNode(Node node, String schemaId, ExecutionMode mode) {
        return outputReportingService.executeOutputNode(node, schemaId, mode);
    }

    public String executeSummaryReportNode(Node node, String schemaId, Map<String, Object> config, String input) {
        return outputReportingService.executeSummaryReportNode(node, schemaId, config, input);
    }

    public String executeCommandNode(Node node, String schemaId) {
        return utilityService.executeCommandNode(node, schemaId);
    }

    public String executeFileWriteNode(Node node, String schemaId) {
        return utilityService.executeFileWriteNode(node, schemaId);
    }

    public String executeSubagentNode(Node node, String schemaId, AtomicBoolean cancelFlag, ExecutionMode mode) {
        return utilityService.executeSubagentNode(node, schemaId, cancelFlag, mode);
    }

    // ────────────────────────── schema-builder / verifier / review (delegated to strategies) ──────────────────────────

    public String executeSchemaBuilderNode(Node node, String schemaId, String resolvedModel) {
        return schemaBuilderStrategy.executeSchemaBuilderNode(node, schemaId, resolvedModel);
    }

    public String executeVerifierNode(Node node, String schemaId, String resolvedModel) {
        return verifierStrategy.executeVerifierNode(node, schemaId, resolvedModel);
    }

    public String executeReviewNode(Node node, String schemaId, String resolvedModel) {
        return reviewStrategy.executeReviewNode(node, schemaId, resolvedModel);
    }

    // ────────────────────────── draft nodes (delegated to DraftNodeStrategy) ──────────────────────────

    public String executeDraftNode(Node node, String schemaId, String resolvedModel) {
        return draftStrategy.executeDraftNode(node, schemaId, resolvedModel);
    }

    // ────────────────────────── Facade delegations (for SchemaServiceImpl) ──────────────────────────

    boolean evaluateConditionPublic(String expr, java.util.Map<String, Object> ctx) { return utilityService.evaluateCondition(expr, ctx); }
    String interpolateVariablesPublic(String text, WorkflowSchema schema, java.util.Map<String, Object> preds) { return utilityService.interpolateVariables(text, schema, preds); }
    String buildContextBlockPublic(java.util.Map<String, Object> preds) { return utilityService.buildContextBlock(preds); }
    String writeOutputPublic(String outputType, String filePath, String fileFormat, String content) { return outputReportingService.writeOutput(outputType, filePath, fileFormat, content); }
    boolean sleepWithCancelPublic(long millis, java.util.concurrent.atomic.AtomicBoolean cancelFlag) { return utilityService.sleepWithCancel(millis, cancelFlag); }
    String executeOutputNodePublic(Node node, String schemaId, ExecutionMode mode) { return outputReportingService.executeOutputNode(node, schemaId, mode); }
}
