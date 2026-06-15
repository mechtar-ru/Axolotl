package com.agent.orchestrator.service;

import com.agent.orchestrator.context.ContextAssembler;
import com.agent.orchestrator.context.ContextBlock;
import com.agent.orchestrator.context.ContextPriority;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.llm.LlmUsage;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.PlanStep;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Strategy for executing agent-type nodes (agent, tool-agent, simulation, analysis).
 * Uses ExecutionUtilityService for shared helper methods.
 */
@Component
public class AgentNodeStrategy implements NodeExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(AgentNodeStrategy.class);

    private final ExecutionUtilityService utilityService;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final ToolExecutor toolExecutor;
    private final Neo4jSchemaRepository schemaRepository;
    private final ProjectContextBuilder projectContextBuilder;
    private final ExecutionStateManager stateManager;
    private final ReasoningCapture reasoningCapture;
    private final PlanStepService planStepService;
    private final ContextAssembler contextAssembler;
    private final ToolExecutionService toolExecutionService;
    private final MagicContextIndexer mcIndexer;
    private final MagicContextRetriever mcRetriever;
    private final FlutterScaffoldHelper flutterScaffoldHelper;
    private final FixPassOrchestrator fixPassOrchestrator;

    public AgentNodeStrategy(ExecutionUtilityService utilityService,
                              LlmService llmService,
                              ExecutionWebSocketHandler webSocketHandler,
                              MemPalaceClient memPalaceClient,
                              ToolExecutor toolExecutor,
                              Neo4jSchemaRepository schemaRepository,
                              ProjectContextBuilder projectContextBuilder,
                              ExecutionStateManager stateManager,
                              ReasoningCapture reasoningCapture,
                              PlanStepService planStepService,
                              ContextAssembler contextAssembler,
                              ToolExecutionService toolExecutionService,
                              MagicContextIndexer mcIndexer,
                              MagicContextRetriever mcRetriever,
                              FlutterScaffoldHelper flutterScaffoldHelper,
                              FixPassOrchestrator fixPassOrchestrator) {
        this.utilityService = utilityService;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.toolExecutor = toolExecutor;
        this.schemaRepository = schemaRepository;
        this.projectContextBuilder = projectContextBuilder;
        this.stateManager = stateManager;
        this.reasoningCapture = reasoningCapture;
        this.planStepService = planStepService;
        this.contextAssembler = contextAssembler;
        this.toolExecutionService = toolExecutionService;
        this.mcIndexer = mcIndexer;
        this.mcRetriever = mcRetriever;
        this.flutterScaffoldHelper = flutterScaffoldHelper;
        this.fixPassOrchestrator = fixPassOrchestrator;
    }

    @Override
    public String supportedNodeType() {
        return "agent";
    }

    @Override
    public Map<String, Object> executeNode(Node node, NodeExecution nodeExec, WorkflowSchema schema,
                                             List<Node> allNodes, List<Edge> edges,
                                             Map<String, Object> executionContext, String schemaId) {
        String resolvedModel = (String) executionContext.getOrDefault("model", "");
        String result = executeAgentNode(node, schemaId, resolvedModel);
        return Map.of("result", result);
    }

    // ─── Agent execution ───

    public String executeAgentNode(Node node, String schemaId, String resolvedModel) {
        boolean useTools = node.getData() != null && node.getData().getEnabledTools() != null
                && !node.getData().getEnabledTools().isEmpty();

        if (useTools) {
            return executeToolAgentNode(node, schemaId, resolvedModel);
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Отправка запроса к AI");
            webSocketHandler.sendLog(schemaId, "info", "Отправка запроса к LLM", node.getId());
        }

        String prompt = node.getData() != null && node.getData().getUserPrompt() != null
                ? node.getData().getUserPrompt()
                : "Анализируй данные";
        String systemPrompt = node.getData() != null ? node.getData().getSystemPrompt() : null;

        String model = resolvedModel;
        if (model == null || model.isBlank()) {
            model = utilityService.resolveModel(node.getData() != null ? node.getData().getModel() : null,
                    null, null, null);
        }

        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
        Map<String, Object> predecessorResults = utilityService.collectPredecessorResults(currentSchema, node.getId());
        String contextBlockText = utilityService.buildContextBlock(predecessorResults);

        prompt = utilityService.interpolateVariables(prompt, currentSchema, predecessorResults);
        if (systemPrompt != null) {
            systemPrompt = utilityService.interpolateVariables(systemPrompt, currentSchema, predecessorResults);
        }

        // Dynamic token budget: collect all additional context, prioritize and truncate
        {
            List<ContextBlock> ctxBlocks = new ArrayList<>();

            // Try Magic Context RAG first, fall back to flat predecessor block
            String mcQuery = (prompt != null && !prompt.isBlank() ? prompt : "task")
                    + " " + (node.getName() != null ? node.getName() : "");
            String mcContext = mcRetriever.retrieveRelevantContext(mcQuery, schemaId);
            if (!mcContext.isEmpty()) {
                ctxBlocks.add(new ContextBlock("mcContext", mcContext, ContextPriority.MEDIUM));
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                            "Magic Context retrieval active (" + mcContext.length() + " chars)", node.getId());
                }
            } else if (!contextBlockText.isEmpty()) {
                ctxBlocks.add(new ContextBlock("predecessorResults",
                        "Контекст от предыдущих узлов:\n" + contextBlockText, ContextPriority.MEDIUM));
            }

            if (memPalaceClient.isEnabled()) {
                String memoryCtx = memPalaceClient.buildGraphContext(prompt, 5);
                if (!memoryCtx.isEmpty()) {
                    ctxBlocks.add(new ContextBlock("mempalace", memoryCtx, ContextPriority.EXPERIMENTAL));
                }
            }

            if (currentSchema != null && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
                try {
                    String projectCtx = projectContextBuilder.buildContext(
                            currentSchema.getTargetPath(), currentSchema.getWorkspaceId(), schemaId);
                    if (!projectCtx.isEmpty()) {
                        ctxBlocks.add(new ContextBlock("projectContext",
                                "=== Project Context ===\n" + projectCtx, ContextPriority.LOW));
                    }
                } catch (Exception e) {
                    log.warn("Failed to build project context: {}", e.getMessage());
                }
            }

            // Plan step context
            String planCtx = buildPlanStepContext(schemaId);
            if (!planCtx.isBlank()) {
                ctxBlocks.add(new ContextBlock("planSteps", planCtx, ContextPriority.HIGH));
            }

            // Determine budget (0 = disabled = unlimited)
            int budget = resolveBudget(node);

            // Assemble with priority-based (budget > 0) or pass-through (budget = 0)
            var ctxResult = contextAssembler.assemble(ctxBlocks, budget);

            // Append assembled context to system prompt
            if (!ctxResult.text().isEmpty()) {
                systemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "") + ctxResult.text();
            }

            // WebSocket observability — send context stats
            if (webSocketHandler != null) {
                try {
                    String statsJson = buildContextStatsJson(ctxResult);
                    webSocketHandler.sendLog(schemaId, "info", "Context budget: " + statsJson, node.getId());
                } catch (Exception ignored) {}
            }
        }

        LlmUsage usage = new LlmUsage();
        LlmResponse streamingResp = llmService.streamingChat(model, systemPrompt, prompt, null,
                token -> {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendToken(schemaId, node.getId(), token);
                    }
                }, usage);
        String result = streamingResp.text();
        if (streamingResp.reasoning() != null) {
            reasoningCapture.capture(node.getId(), streamingResp.reasoning());
        }

        if (usage.getTotalTokens() > 0) {
            stateManager.recordTokenUsage(schemaId + ":" + node.getId(), usage);
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 70, "Ответ получен");
            webSocketHandler.sendLog(schemaId, "info", "Ответ от LLM получен", node.getId());
        }

        if (result != null && !result.isBlank()) {
            Map<String, Object> extracted = toolExecutionService.extractGeneratedFiles(result);
            if (extracted != null) {
                stateManager.getGeneratedFilesRegistry().put(schemaId + ":" + node.getId(), extracted);
            }
        }

        if (memPalaceClient.isEnabled() && result != null && !result.isBlank()) {
            memPalaceClient.addDrawer("axolotl", "agent-results",
                    node.getName() + ": " + result.substring(0, Math.min(500, result.length())),
                    "schema:" + schemaId);
        }

        return result;
    }

    public String executeToolAgentNode(Node node, String schemaId, String resolvedModel) {
        String agentType = node.getData().getAgentType() != null ? node.getData().getAgentType() : "code-agent";
        List<String> enabledTools = node.getData().getEnabledTools();
        int maxToolCalls = node.getData().getMaxToolCalls() > 0 ? node.getData().getMaxToolCalls() : 10;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Инициализация агента с инструментами");
            webSocketHandler.sendLog(schemaId, "info", "Агент типа: " + agentType + ", инструменты: " + enabledTools, node.getId());
        }

        // Default tools per agent type
        if (enabledTools == null || enabledTools.isEmpty()) {
            switch (agentType) {
                case "doc-agent":
                    enabledTools = List.of("file_read", "file_write", "directory_read");
                    break;
                case "planner":
                case "prep":
                    enabledTools = List.of("file_read", "file_write", "directory_read");
                    break;
                default:
                    enabledTools = List.of("file_read", "file_write", "bash", "grep", "directory_read");
                    break;
            }
            node.getData().setEnabledTools(enabledTools);
        }

        String prompt = node.getData().getUserPrompt();
        String systemPrompt = node.getData().getSystemPrompt();

        // Agent-type-specific system prompt augmentation
        if (systemPrompt == null || systemPrompt.isBlank()) {
            switch (agentType) {
                case "doc-agent":
                    systemPrompt = buildDocAgentPrompt(node, schemaId);
                    break;
                case "planner":
                    systemPrompt = buildPlannerPrompt(node, schemaId);
                    break;
                case "prep":
                    systemPrompt = buildPrepPrompt(node, schemaId);
                    break;
                default:
                    systemPrompt = "You are a coding agent. Implement the requested features.";
                    break;
            }
        }

        // Append FLUTTER workflow instructions for code agents on FLUTTER projects
        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
        if (currentSchema != null && "FLUTTER".equals(currentSchema.getAppType())
                && !systemPrompt.contains("FLUTTER WORKFLOW")) {
            systemPrompt += "\n\n" + """
                    ==== FLUTTER WORKFLOW ====
                    1. Create all files for the app via file_write (lib/main.dart must exist)
                    2. Write COMPLETE implementations (not stubs) via file_write
                    3. File structure: lib/{main.dart, app.dart, screens/*.dart, models/*.dart, services/*.dart}
                    4. After writing all files, run: flutter pub add provider go_router sqflite intl
                    5. Verify with build_app
                    """.trim();
        }

        String model = resolvedModel;
        if (model == null || model.isBlank()) {
            model = utilityService.resolveModel(node.getData().getModel(), null, null, null);
        }
        Map<String, Object> predecessorResults = utilityService.collectPredecessorResults(currentSchema, node.getId());
        String contextBlock = utilityService.buildContextBlock(predecessorResults);

        prompt = utilityService.interpolateVariables(prompt, currentSchema, predecessorResults);
        if (systemPrompt != null) {
            systemPrompt = utilityService.interpolateVariables(systemPrompt, currentSchema, predecessorResults);
        }

        // Dynamic token budget: collect all additional context, prioritize and truncate
        {
            List<ContextBlock> ctxBlocks = new ArrayList<>();

            // Try Magic Context RAG first, fall back to flat predecessor block
            String mcQuery = (prompt != null && !prompt.isBlank() ? prompt : "task")
                    + " " + (node.getName() != null ? node.getName() : "");
            String mcContext = mcRetriever.retrieveRelevantContext(mcQuery, schemaId);
            if (!mcContext.isEmpty()) {
                ctxBlocks.add(new ContextBlock("mcContext", mcContext, ContextPriority.MEDIUM));
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                            "Magic Context retrieval active (" + mcContext.length() + " chars)", node.getId());
                }
            } else if (!contextBlock.isEmpty()) {
                ctxBlocks.add(new ContextBlock("predecessorResults",
                        "Контекст от предыдущих узлов:\n" + contextBlock, ContextPriority.MEDIUM));
            }

            if (memPalaceClient.isEnabled()) {
                String memoryCtx = memPalaceClient.buildGraphContext(prompt, 5);
                if (!memoryCtx.isEmpty()) {
                    ctxBlocks.add(new ContextBlock("mempalace", memoryCtx, ContextPriority.EXPERIMENTAL));
                }
            }

            if (currentSchema != null && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
                try {
                    String projectCtx = projectContextBuilder.buildContext(
                            currentSchema.getTargetPath(), currentSchema.getWorkspaceId(), schemaId);
                    if (!projectCtx.isEmpty()) {
                        ctxBlocks.add(new ContextBlock("projectContext",
                                "=== Project Context ===\n" + projectCtx, ContextPriority.LOW));
                    }
                } catch (Exception e) {
                    log.warn("Failed to build project context: {}", e.getMessage());
                }
            }

            if (node.getData() != null && node.getData().getConfig() != null
                    && Boolean.TRUE.equals(node.getData().getConfig().get("requireDiffReview"))) {
                ctxBlocks.add(new ContextBlock("diffReview",
                        "IMPORTANT: Diff review mode enabled.\n"
                        + "You MUST modify existing files using file_write on their current paths.\n"
                        + "Do NOT create new files unless absolutely necessary.\n"
                        + "After you finish, the pipeline will pause for human diff review.\n"
                        + "Always read the existing file content first before modifying it.\n",
                        ContextPriority.HIGH));
            }

            // Plan step context
            String planCtx = buildPlanStepContext(schemaId);
            if (!planCtx.isBlank()) {
                ctxBlocks.add(new ContextBlock("planSteps", planCtx, ContextPriority.HIGH));
            }

            // Tool instructions — budgeted as HIGH priority (was previously unbounded before assembly)
            if (!"doc-agent".equals(agentType)) {
                String toolInstructions = toolExecutionService.buildToolInstructions(enabledTools);
                if (toolInstructions != null && !toolInstructions.isBlank()) {
                    ctxBlocks.add(new ContextBlock("toolInstructions", toolInstructions, ContextPriority.HIGH));
                }
            } else {
                String docInstructions = buildDocAgentToolInstructions(enabledTools);
                if (docInstructions != null && !docInstructions.isBlank()) {
                    ctxBlocks.add(new ContextBlock("toolInstructions", docInstructions, ContextPriority.HIGH));
                }
            }

            // Determine budget (0 = disabled = unlimited)
            int budget = resolveBudget(node);

            // Assemble with priority-based (budget > 0) or pass-through (budget = 0)
            var ctxResult = contextAssembler.assemble(ctxBlocks, budget);

            // Append assembled context to system prompt
            if (!ctxResult.text().isEmpty()) {
                systemPrompt += "\n\n" + ctxResult.text();
            }

            // WebSocket observability — send context stats
            if (webSocketHandler != null) {
                try {
                    String statsJson = buildContextStatsJson(ctxResult);
                    webSocketHandler.sendLog(schemaId, "info", "Context budget: " + statsJson, node.getId());
                } catch (Exception ignored) {}
            }
        }

        List<Node.Message> messages = new ArrayList<>();
        messages.add(new Node.Message("system", systemPrompt));
        messages.add(new Node.Message("user", prompt));

                // Auto-scaffold for FLUTTER projects: run flutter create before agent starts
                // to ensure pubspec.yaml and lib/ exist (prevents "lib/ not found" loops)
                if (currentSchema != null && "FLUTTER".equals(currentSchema.getAppType())
                        && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
                    flutterScaffoldHelper.ensureFlutterScaffold(currentSchema.getTargetPath());
                }

                // Create architecture plan from planner model
        String plannerModelBase = node.getData() != null ? node.getData().getPlannerModel() : null;
        if (plannerModelBase == null && node.getData() != null && node.getData().getConfig() != null) {
            Object pm = node.getData().getConfig().get("plannerModel");
            if (pm instanceof String s) plannerModelBase = s;
        }
        final String plannerModel = plannerModelBase;
        boolean hasPlanner = plannerModel != null && !plannerModel.isBlank();
        if (hasPlanner) {
            try {
                // Architecture plan only (fast, ~60s). Executor implements via file_write.
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                        "Calling planner for architecture plan...", node.getId());
                }
                String plannerSysPrompt = "You are a senior Flutter architect. "
                    + "List every file needed for the app (with path, purpose, key classes). "
                    + "Keep it concise (under 3000 chars). For each file, say what it does and what goes in it.";
                String plannerPrompt = "Design the architecture for:\n" + prompt;

                // Hard timeout: 60s — OpenRouter free tier hangs on long requests
                java.util.concurrent.CompletableFuture<LlmResponse> future =
                    java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        llmService.chat(plannerModel, plannerSysPrompt, plannerPrompt, null, null));
                LlmResponse plannerResp = future.get(60, java.util.concurrent.TimeUnit.SECONDS);
                String archPlan = plannerResp != null ? plannerResp.text() : "";
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                        "Planner arch response: " + archPlan.length() + " chars", node.getId());
                }

                // Inject architecture plan as context for the executor
                if (!archPlan.isBlank()) {
                    String planContext = "## Architecture Plan (generated by " + plannerModel + ")\n"
                        + archPlan.substring(0, Math.min(archPlan.length(), 4000))
                        + "\n\n**Follow this plan. Implement ALL files using file_write.**";
                    messages.add(new Node.Message("assistant", planContext));
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "info",
                            "Architecture plan injected into executor context (" + archPlan.length() + " chars)", node.getId());
                    }
                }
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("Planner arch call timed out (60s)");
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "warn",
                        "Planner arch call timed out after 60s", node.getId());
                }
            } catch (Exception e) {
                log.warn("Planner arch call failed: {}", e.getMessage());
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "warn",
                        "Planner failed: " + e.getMessage(), node.getId());
                }
            }
        }

        // Build structured tools for LLM API request body
        Map<String, Object> chatConfig = null;
        if (enabledTools != null && !enabledTools.isEmpty()) {
            List<Map<String, Object>> structuredToolDefs = new ArrayList<>();
            for (String toolName : enabledTools) {
                com.agent.orchestrator.model.Tool toolDef = toolExecutor.getTool(toolName);
                if (toolDef != null) {
                    Map<String, Object> def = new HashMap<>();
                    def.put("name", toolDef.getName());
                    def.put("description", toolDef.getDescription());
                    def.put("input_schema", toolDef.getInputSchema());
                    structuredToolDefs.add(def);
                }
            }
            if (!structuredToolDefs.isEmpty()) {
                chatConfig = new HashMap<>();
                chatConfig.put("_tools", structuredToolDefs);
            }
        }

        StringBuilder fullResponse = new StringBuilder();
        int toolCallCount = 0;
        int iterationCount = 0;
        long totalStartTime = System.currentTimeMillis();
        String lastResponse = null;
        LlmUsage totalUsage = new LlmUsage();

        while (toolCallCount < maxToolCalls) {
            long iterationStartTime = System.currentTimeMillis();
            iterationCount++;

            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20 + (toolCallCount * 5),
                        "Итерация " + iterationCount + " из " + maxToolCalls);
                webSocketHandler.sendLog(schemaId, "info", "Итерация " + iterationCount, node.getId());
            }

            LlmUsage iterUsage = new LlmUsage();
            LlmResponse iterResp = llmService.chat(model, null,
                    toolExecutionService.buildMessagesForToolCall(messages), chatConfig, iterUsage);
            lastResponse = iterResp.text();
            if (iterResp.reasoning() != null) {
                reasoningCapture.capture(schemaId, node.getId(), iterationCount, iterResp.reasoning());
            }
            totalUsage.add(iterUsage);
            messages.add(new Node.Message("assistant", lastResponse));
            fullResponse.append(lastResponse).append("\n");

            List<Map<String, Object>> toolCalls = toolExecutionService.parseToolCalls(lastResponse);
            if (toolCalls.isEmpty()) {
                long iterDuration = System.currentTimeMillis() - iterationStartTime;
                if (webSocketHandler != null) {
                    webSocketHandler.sendIteration(schemaId, node.getId(), iterationCount, iterDuration, 0, 0);
                }
                break;
            }

            int toolsInThisIteration = 0;
            for (Map<String, Object> toolCall : toolCalls) {
                if (toolCallCount >= maxToolCalls) {
                    toolExecutionService.sendUserApprovalRequest(schemaId, node.getId(), toolCallCount, maxToolCalls);
                    break;
                }

                String toolId = (String) toolCall.get("name");
                if (toolId == null) {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "warn", "Skipped tool call with null name", node.getId());
                    }
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) toolCall.get("arguments");

                String targetPath = currentSchema != null ? currentSchema.getTargetPath() : null;
                String projectType = currentSchema != null ? currentSchema.getProjectType() : null;
                String toolResult = toolExecutionService.executeToolCall(toolId, args, node, schemaId, targetPath, projectType);
                messages.add(new Node.Message("tool", toolResult));
                String toolCallId = (String) toolCall.get("id");
                if (toolCallId != null) {
                    messages.add(new Node.Message("tool_call_id", toolCallId));
                }

                if (webSocketHandler != null) {
                    String logMsg = toolResult.length() > 100 ? toolResult.substring(0, 100) + "..." : toolResult;
                    webSocketHandler.sendLog(schemaId, "info", "Инструмент " + toolId + ": " + logMsg, node.getId());
                }

                toolCallCount++;
                toolsInThisIteration++;
            }

            long iterDuration = System.currentTimeMillis() - iterationStartTime;
            if (webSocketHandler != null) {
                webSocketHandler.sendIteration(schemaId, node.getId(), iterationCount, iterDuration, toolsInThisIteration, 0);
            }

            if (toolCalls.isEmpty() || toolCallCount >= maxToolCalls) {
                break;
            }
        }

        long totalDuration = System.currentTimeMillis() - totalStartTime;
        if (totalUsage.getTotalTokens() > 0) {
            stateManager.recordTokenUsage(schemaId + ":" + node.getId(), totalUsage);
        }
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 90, "Завершение");
            webSocketHandler.sendLog(schemaId, "info", "Выполнено инструментов: " + toolCallCount, node.getId());
            webSocketHandler.sendTrajectoryComplete(schemaId, node.getId(), iterationCount, totalDuration, toolCallCount, 0, 0, 0.0);
        }

        if (memPalaceClient.isEnabled() && fullResponse.length() > 0) {
            String summary = fullResponse.substring(0, Math.min(500, fullResponse.length()));
            memPalaceClient.addDrawer("axolotl", "agent-results", node.getName() + ": " + summary, "schema:" + schemaId);
            memPalaceClient.addDrawer("axolotl", "trajectories",
                    "Итераций: " + iterationCount + ", инструментов: " + toolCallCount + ", время: " + totalDuration + "мс",
                    "schema:" + schemaId + ",node:" + node.getId());
        }

        String finalResponse = lastResponse != null ? lastResponse : fullResponse.toString();
        if (finalResponse != null && !finalResponse.isBlank()) {
            Map<String, Object> extracted = toolExecutionService.extractGeneratedFiles(finalResponse);
            if (extracted != null) {
                stateManager.getGeneratedFilesRegistry().put(schemaId + ":" + node.getId(), extracted);
            }
        }

        // Append file changes summary
        Map<String, String> changes = stateManager.getFileChanges(schemaId, node.getId());
        int actualFileCount = changes.size();

        // Compare against expectedFileCount from config
        Map<String, Object> agentConfig = node.getData() != null ? node.getData().getConfig() : null;
        if (agentConfig != null && agentConfig.get("expectedFileCount") instanceof Number) {
            int expected = ((Number) agentConfig.get("expectedFileCount")).intValue();
            if (expected > 0 && actualFileCount < expected) {
                String warning = String.format(
                        "\n\n[WARNING] Expected at least %d file(s), but created only %d file(s).",
                        expected, actualFileCount);
                finalResponse += warning;
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "warning",
                            "Expected " + expected + " file(s), created " + actualFileCount, node.getId());
                }
            }
        }

        if (!changes.isEmpty()) {
            StringBuilder changeSummary = new StringBuilder("\n\n[FILE CHANGES]\n");
            for (var entry : changes.entrySet()) {
                String status = "created".equals(entry.getValue()) ? "✅ Created" : "📝 Modified";
                changeSummary.append("  ").append(status).append(": ").append(entry.getKey()).append("\n");
            }
            finalResponse += changeSummary.toString();
            stateManager.clearFileChanges(schemaId, node.getId());
        }

        // Auto build check — if enabled in stage config, run build_app after agent completes
        Map<String, Object> config = node.getData() != null ? node.getData().getConfig() : null;
        boolean autoBuild = config != null && Boolean.TRUE.equals(config.get("autoBuildCheck"));
        if (autoBuild && currentSchema != null && currentSchema.getTargetPath() != null
                && !currentSchema.getTargetPath().isBlank()) {
            finalResponse += buildAndReport(currentSchema, schemaId, node.getId());
        }

        // For FLUTTER projects, auto-install dependencies and verify build after agent completes
        if (currentSchema != null && "FLUTTER".equals(currentSchema.getAppType())
                && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
            try {
                String targetDir = currentSchema.getTargetPath();
                String[] baseDeps = {"provider", "go_router", "intl", "path_provider", "sqflite", "fl_chart", "http"};
                for (String dep : baseDeps) {
                    ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                            "cd " + targetDir + " && flutter pub add " + dep + " 2>/dev/null");
                    Process p = pb.start();
                    p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                }
                // Run flutter pub get to ensure lockfile is fresh
                ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                        "cd " + targetDir + " && flutter pub get");
                Process p = pb.start();
                p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
                finalResponse += "\n\n[DEPENDENCIES] Auto-installed Flutter packages";

                // Auto-fix Flutter compilation errors using the FixPassOrchestrator
                FixPassOrchestrator.FixPassResult fixResult = fixPassOrchestrator.runFixPass(
                        targetDir, node.getId(), schemaId, currentSchema.getName());
                // Build result report from fix pass result
                if (fixResult != null) {
                    if (fixResult.fixedFiles() != null && !fixResult.fixedFiles().isEmpty()) {
                        for (String msg : fixResult.fixedFiles()) {
                            finalResponse += "\n" + msg;
                        }
                    }
                    if (fixResult.errorsRemaining() > 0) {
                        finalResponse += "\n[FIX PASS] " + fixResult.errorsRemaining() + " error(s) remaining after fix pass";
                    }
                }
            } catch (Exception e) {
                log.warn("Flutter post-processing failed (non-fatal): {}", e.getMessage());
            }
        }

        return finalResponse;
    }

    // ─── Plan Step context injection ───

    /**
     * Build a plan step context block from Neo4j plan steps.
     * Appends at the end of the system prompt if plan steps exist for this schema.
     */
    private String buildPlanStepContext(String schemaId) {
        try {
            List<PlanStep> steps = planStepService.getSteps(schemaId);
            if (steps == null || steps.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("\n\n## Plan Steps\n");
            List<PlanStep> ready = planStepService.getReadySteps(schemaId);
            Set<String> readyIds = ready != null ? ready.stream()
                    .map(PlanStep::getId)
                    .collect(java.util.stream.Collectors.toSet()) : java.util.Collections.emptySet();

            sb.append("Status: ").append(steps.size()).append(" total steps, ")
                    .append(ready != null ? ready.size() : 0).append(" ready for execution\n\n");

            for (PlanStep s : steps) {
                sb.append("- [").append(s.getStatus()).append("] #").append(s.getStepId())
                        .append(": ").append(s.getTitle());
                if (readyIds.contains(s.getId())) {
                    sb.append(" ← READY");
                }
                if (s.getDependsOn() != null && !s.getDependsOn().isEmpty()) {
                    sb.append(" (depends_on: ").append(String.join(", ", s.getDependsOn())).append(")");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not load plan steps for schema {}: {}", schemaId, e.getMessage());
            return "";
        }
    }

    // ─── Agent-type-specific prompt builders ───

    private String buildDocAgentPrompt(Node node, String schemaId) {
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        String targetPath = schema != null && schema.getTargetPath() != null ? schema.getTargetPath() : ".";
        String schemaName = schema != null && schema.getName() != null ? schema.getName() : "project";

        return """
            You are a documentation agent for project "%s".
            Your task is to update project documentation based on recent changes.

            ## Tools
            - file_read — read existing documentation files
            - file_write — create or update files (creates .bak backups)
            - directory_read — list files in a directory

            ## Documentation structure
            - %s/.axolotl/spec.md — project specification (APPEND new features)
            - %s/.axolotl/changelog.md — session changelog (APPEND entry)
            - %s/design/ — feature design docs (CREATE for new features)
            - %s/README.md — project overview (UPDATE only if core purpose changed)

            ## Rules
            1. READ existing files BEFORE modifying — you must APPEND, not replace.
            2. spec.md: add new features as new sections with ## heading. Preserve all existing content.
            3. changelog.md: append entry in format:
               ## YYYY-MM-DD — <session title>
               - <change>
            4. design/: create <feature-name>.md for each feature >1 file change.
            5. README: update only if the project's core purpose/description has changed.
            6. Do NOT modify .axolotl/plan.md — reserved for future task planning.
            7. After writing, output summary JSON:
               {"updatedDocs": ["path1.md"], "createdDocs": ["design/feature-x.md"]}
            """.formatted(schemaName, targetPath, targetPath, targetPath, targetPath);
    }

    private String buildPlannerPrompt(Node node, String schemaId) {
        return """
            You are a project planner. Based on the approved design from the upstream stage,
            create a detailed implementation plan with numbered steps and dependencies.

            Output the plan using this exact format:

            ```plan
            step: 1
            title: "Setup project structure"
            description: "Initialize directories and config files"
            depends_on: []

            step: 2
            title: "Implement core models"
            description: "Create data models and database schema"
            depends_on: [1]
            ```

            Rules:
            - Each step must have a unique number
            - depends_on lists step numbers that must be completed first
            - Steps without dependencies should have depends_on: []
            - 3-10 steps recommended for a typical feature
            - Steps must be implementable in 1-4 hours each
            """;
    }

    private String buildPrepPrompt(Node node, String schemaId) {
        return """
            You are a preparation agent. Based on the approved plan and design,
            generate the following artifacts:

            1. Write plan/pseudo-frontend.md — pseudocode for UI components:
               - Component tree with props and state
               - Route definitions
               - Event handlers and data flow
               - API calls and their signatures

            2. Write plan/pseudo-backend.md — pseudocode for backend:
               - API endpoints (method, path, request/response shapes)
               - Data models and database schema
               - Service layer interfaces
               - Middleware/auth flow

            3. Write tests/ — test stubs matching the pseudocode API:
               - Frontend: component mount, user interaction, API mock tests
               - Backend: endpoint tests, model validation, integration tests

            Use file_write to create each file. Read existing files first.
            Output a summary of created files at the end.
            """;
    }

    private String buildDocAgentToolInstructions(List<String> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Available tools\n");
        for (String t : tools) {
            switch (t) {
                case "file_read" -> sb.append("- file_read: Read file contents. Usage: {\"name\":\"file_read\",\"arguments\":{\"path\":\"file.md\"}}\n");
                case "file_write" -> sb.append("- file_write: Write content to file (creates .bak for existing files). Usage: {\"name\":\"file_write\",\"arguments\":{\"path\":\"file.md\",\"content\":\"...\"}}\n");
                case "directory_read" -> sb.append("- directory_read: List files in a directory. Usage: {\"name\":\"directory_read\",\"arguments\":{\"path\":\".\"}}\n");
                default -> sb.append("- ").append(t).append("\n");
            }
        }
        sb.append("\nIMPORTANT: Always read existing files before modifying them. APPEND to spec.md, don't overwrite.\n");
        return sb.toString();
    }

    /**
     * Run build checks for the project after agent completes.
     * For Flutter: runs main build + iOS build (macOS only).
     */
    private String buildAndReport(WorkflowSchema currentSchema, String schemaId, String nodeId) {
        String targetPath = currentSchema.getTargetPath();
        String ptStr = currentSchema.getProjectType();
        com.agent.orchestrator.model.ProjectType pt = com.agent.orchestrator.model.ProjectType.fromString(ptStr);
        var buildCmd = pt.getBuildCommand();
        var sdkCheck = buildCmd.getFirst(); // e.g. "flutter", "npm", "python3", "go", "cargo"

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info",
                    "Auto build check enabled — verifying build...", nodeId);
        }

        var results = new java.util.ArrayList<String>();

        try {
            // Check SDK binary exists
            var whichProc = new ProcessBuilder("which", sdkCheck)
                    .redirectErrorStream(true).start();
            if (!whichProc.waitFor(5, TimeUnit.SECONDS) || whichProc.exitValue() != 0) {
                return "\n\n[BUILD CHECK] " + sdkCheck + " not found. Required for " + pt.getDisplayName() + " projects.";
            }

            // Run the main build command
            ProcessBuilder buildPb = new ProcessBuilder(buildCmd);
            buildPb.directory(new java.io.File(targetPath));
            buildPb.redirectErrorStream(true);
            Process buildProc = buildPb.start();
            boolean finished = buildProc.waitFor(300, TimeUnit.SECONDS);
            if (finished && buildProc.exitValue() == 0) {
                results.add("✅ " + pt.getDisplayName() + " build succeeded");
            } else {
                String out = finished
                    ? new String(buildProc.getInputStream().readAllBytes())
                    : "(timed out)";
                results.add("❌ " + pt.getDisplayName() + " build failed");
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "error",
                            "Main build failed:\n" + out, nodeId);
                }
            }

            // For Flutter on macOS, also try iOS build (non-blocking)
            if (pt == com.agent.orchestrator.model.ProjectType.FLUTTER
                    && System.getProperty("os.name", "").toLowerCase().contains("mac")) {
                try {
                    var xcSelect = new ProcessBuilder("xcode-select", "-p")
                        .redirectErrorStream(true).start();
                    if (xcSelect.waitFor(5, TimeUnit.SECONDS) && xcSelect.exitValue() == 0) {
                        var iosCmd = java.util.List.of("flutter", "build", "ios", "--no-codesign", "--debug");
                        var iosPb = new ProcessBuilder(iosCmd)
                            .directory(new java.io.File(targetPath))
                            .redirectErrorStream(true);
                        Process iosProc = iosPb.start();
                        boolean iosDone = iosProc.waitFor(300, TimeUnit.SECONDS);
                        if (iosDone && iosProc.exitValue() == 0) {
                            results.add("✅ iOS build succeeded");
                        } else {
                            results.add("⚠️ iOS build: " + (iosDone ? "failed" : "timed out"));
                        }
                    } else {
                        results.add("⚠️ iOS build: Xcode not found (skip)");
                    }
                } catch (Exception e) {
                    results.add("⚠️ iOS build: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error", "Build check error: " + e.getMessage(), nodeId);
            }
            return "\n\n[BUILD CHECK] Error: " + e.getMessage();
        }

        var report = new StringBuilder("\n\n[BUILD CHECK RESULTS]\n");
        for (String r : results) {
            report.append("  ").append(r).append("\n");
        }
        return report.toString();
    }

    public String simulateAgentNode(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "DRY_RUN: Симуляция");
            webSocketHandler.sendLog(schemaId, "warning", "DRY_RUN: Симуляция LLM вызова (результат не сохраняется)", node.getId());
        }
        String prompt = node.getData() != null && node.getData().getUserPrompt() != null
                ? node.getData().getUserPrompt() : "Анализируй данные";
        String model = node.getData() != null ? node.getData().getModel() : "unknown";
        String simulatedResult = "[DRY_RUN] Симулированный ответ от " + model
                + "\nПромпт: " + prompt.substring(0, Math.min(100, prompt.length())) + "...";
        if (webSocketHandler != null) {
            webSocketHandler.sendResult(schemaId, node.getId(), simulatedResult);
        }
        return simulatedResult;
    }

    public String analyzeAgentNode(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "ANALYZE: Блокировка мутаций");
            webSocketHandler.sendLog(schemaId, "warning", "ANALYZE: Заблокированы file/API операции", node.getId());
        }
        String prompt = node.getData() != null && node.getData().getUserPrompt() != null
                ? node.getData().getUserPrompt() : "Анализируй данные";
        String model = node.getData() != null ? node.getData().getModel() : "unknown";
        String analysisResult = "[ANALYZE] LLM вызов выполнен, мутации заблокированы\nМодель: "
                + model + "\nПромпт: " + prompt.substring(0, Math.min(100, prompt.length())) + "...";
        if (webSocketHandler != null) {
            webSocketHandler.sendResult(schemaId, node.getId(), analysisResult);
        }
        return analysisResult;
    }

    // ─── Context budget helpers ───

    /**
     * Resolve token budget from node config.
     * Priority: data.contextBudgetTokens > data.config.contextBudgetTokens > 0 (disabled).
     */
    private int resolveBudget(Node node) {
        if (node == null || node.getData() == null) return 0;
        // Check bean field first
        if (node.getData().getContextBudgetTokens() != null
                && node.getData().getContextBudgetTokens() > 0) {
            return node.getData().getContextBudgetTokens();
        }
        // Fallback to config map (set by frontend BlockConfigPanel)
        if (node.getData().getConfig() != null) {
            Object val = node.getData().getConfig().get("contextBudgetTokens");
            if (val instanceof Number && ((Number) val).intValue() > 0) {
                return ((Number) val).intValue();
            }
        }
        return 0; // disabled
    }

    private String buildContextStatsJson(ContextAssembler.AssemblyResult result) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"totalTokens\":").append(result.totalTokens()).append(",");
        sb.append("\"criticalTokens\":").append(result.criticalTokens()).append(",");
        sb.append("\"budgetTokens\":").append(result.budgetTokens()).append(",");
        sb.append("\"blocks\":[");
        boolean first = true;
        for (var stat : result.stats()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"name\":\"").append(escapeJson(stat.name())).append("\",");
            sb.append("\"tokens\":").append(stat.tokens()).append(",");
            sb.append("\"priority\":\"").append(stat.priority().name()).append("\",");
            sb.append("\"included\":").append(stat.included()).append(",");
            sb.append("\"skipped\":").append(stat.skipped()).append(",");
            sb.append("\"truncated\":").append(stat.truncated());
            sb.append("}");
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
