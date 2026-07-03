package com.agent.orchestrator.service;

import com.agent.orchestrator.context.ContextAssembler;
import com.agent.orchestrator.context.ContextBlock;
import com.agent.orchestrator.context.ContextPriority;
import com.fasterxml.jackson.databind.JsonNode;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.llm.LlmUsage;
import com.agent.orchestrator.llm.StreamingResult;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;

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
    private final Executor plannerExecutor = Executors.newVirtualThreadPerTaskExecutor();

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

    @PreDestroy
    public void shutdown() {
        ((ExecutorService) plannerExecutor).shutdownNow();
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
        String result = executeAgentNode(node, schemaId, resolvedModel, null);
        return Map.of("result", result);
    }

    // ─── Agent execution ───

    public String executeAgentNode(Node node, String schemaId, String resolvedModel, AtomicBoolean cancelFlag) {
        // Clear stale file changes from any previous run of this node
        stateManager.clearFileChanges(schemaId, node.getId());

        // Ensure enabledTools is set from config if not already
        if (node.getData() != null && (node.getData().getEnabledTools() == null || node.getData().getEnabledTools().isEmpty())) {
            Map<String, Object> cfg = node.getData().getConfig();
            if (cfg != null) {
                Object tools = cfg.get("enabledTools");
                if (tools == null) tools = cfg.get("tools");
                if (tools instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> toolList = (List<String>) tools;
                    node.getData().setEnabledTools(new ArrayList<>(toolList));
                }
            }
        }

        boolean useTools = node.getData() != null && node.getData().getEnabledTools() != null
                && !node.getData().getEnabledTools().isEmpty();

        if (useTools) {
            return executeToolAgentNode(node, schemaId, resolvedModel, cancelFlag);
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

        // Assemble context (Magic Context RAG, project context, plan steps)
        systemPrompt = assembleAgentContext(node, currentSchema, schemaId, prompt, systemPrompt, contextBlockText, false, null, null);

        LlmUsage usage = new LlmUsage();
        // Final copies for lambda capture (variables above may be reassigned)
        final String capturedModel = model;
        final String capturedSystemPrompt = systemPrompt;
        final String capturedPrompt = prompt;
        // Smart per-call timeout: use node timeoutSeconds (capped at 1h) or default 600
        int perCallTimeout = 600;
        if (node != null && node.getData() != null && node.getData().getTimeoutSeconds() != null) {
            perCallTimeout = Math.min(node.getData().getTimeoutSeconds(), 3600);
        }
        LlmResponse streamingResp;
        try {
            streamingResp = CompletableFuture.supplyAsync(() ->
                    llmService.streamingChat(capturedModel, capturedSystemPrompt, capturedPrompt, null,
                            token -> {
                                if (webSocketHandler != null) {
                                    webSocketHandler.sendToken(schemaId, node.getId(), token);
                                }
                            }, usage))
                    .orTimeout(perCallTimeout, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.error("LLM streaming call timed out for node {}", node.getId());
            }
            throw e;
        }
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
            List<Map<String, String>> writtenFiles = collectWrittenFiles(schemaId, node.getId());
            if (!writtenFiles.isEmpty()) {
                stateManager.getGeneratedFilesRegistry().put(schemaId + ":" + node.getId(), writtenFiles);
            }
        }

        if (memPalaceClient.isEnabled() && result != null && !result.isBlank()) {
            memPalaceClient.addDrawer("axolotl", "agent-results",
                    node.getName() + ": " + result.substring(0, Math.min(500, result.length())),
                    "schema:" + schemaId);
        }

        return result;
    }

    public String executeToolAgentNode(Node node, String schemaId, String resolvedModel, AtomicBoolean cancelFlag) {
        String agentType = node.getData().getAgentType() != null ? node.getData().getAgentType() : "code-agent";
        List<String> enabledTools = node.getData().getEnabledTools();
        int maxToolCalls = node.getData().getMaxToolCalls() >= 0 ? node.getData().getMaxToolCalls() : 10;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Инициализация агента с инструментами");
            webSocketHandler.sendLog(schemaId, "info", "Агент типа: " + agentType + ", инструменты: " + enabledTools, node.getId());
        }

        // minIterations: keep agent loop alive even with 0 tool calls (for thinking then acting)
        int minIterations = 0;
        Map<String, Object> nodeConfig = node.getData().getConfig();

        // Default tools per agent type — only if user didn't explicitly set tools
        boolean userSetTools = false;
        if (nodeConfig != null && nodeConfig.containsKey("tools") && nodeConfig.get("tools") instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> configTools = (List<String>) nodeConfig.get("tools");
            enabledTools = new ArrayList<>(configTools);
            node.getData().setEnabledTools(enabledTools);
            userSetTools = true;
        } else if (enabledTools != null && !enabledTools.isEmpty()) {
            userSetTools = true;
        }
        if (!userSetTools) {
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

        if (nodeConfig != null && nodeConfig.get("minIterations") instanceof Number) {
            minIterations = ((Number) nodeConfig.get("minIterations")).intValue();
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

        // Append FLUTTER workflow instructions — opt-in via config.flutterWorkflow
        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
        boolean flutterWorkflow = nodeConfig != null && Boolean.TRUE.equals(nodeConfig.get("flutterWorkflow"));
        if (currentSchema != null && flutterWorkflow
                && !systemPrompt.contains("FLUTTER WORKFLOW")) {
            systemPrompt += "\n\n" + """
                     ==== FLUTTER WORKFLOW ====
                     1. Create all files for the app via file_write (lib/main.dart must exist)
                     2. Write COMPLETE implementations (not stubs) via file_write
                     3. File structure: lib/{main.dart, app.dart, screens/*.dart, models/*.dart, services/*.dart}
                     4. After writing all files, run: flutter pub add provider go_router sqflite intl
                     5. Verify with build_app
                     6. Report: output JSON with generated files list:
                         {"generatedFiles": [{"path": "lib/main.dart", "description": "app entry point"}, ...]}
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

        // ── 1. Context assembly (Magic Context, project context, plan steps, tool instructions) ──
        systemPrompt = assembleAgentContext(node, currentSchema, schemaId, prompt, systemPrompt, contextBlock, true, enabledTools, agentType);

        List<Node.Message> messages = new ArrayList<>();
        messages.add(new Node.Message("system", systemPrompt));
        messages.add(new Node.Message("user", prompt));

        // Auto-scaffold for FLUTTER projects: run flutter create before agent starts
        // to ensure pubspec.yaml and lib/ exist (prevents "lib/ not found" loops)
        if (currentSchema != null && "FLUTTER".equals(currentSchema.getAppType())
                && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
            flutterScaffoldHelper.ensureFlutterScaffold(currentSchema.getTargetPath());
        }

        // ── 2. Planner phase (dual-model architecture plan) ──
        runPlannerPhase(node, schemaId, prompt, messages);

        // Build structured tools for LLM API request body
        Map<String, Object> chatConfig = null;
        List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecs = new ArrayList<>();
        if (enabledTools != null && !enabledTools.isEmpty()) {
            List<Map<String, Object>> structuredToolDefs = new ArrayList<>();
            for (String toolName : enabledTools) {
                com.agent.orchestrator.model.Tool toolDef = toolExecutor.getTool(toolName);
                if (toolDef != null) {
                    Map<String, Object> func = new HashMap<>();
                    String rawName = toolDef.getName();
                    // Ensure name matches OpenAI pattern ^[a-zA-Z0-9_-]+$
                    String safeName = rawName.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
                    func.put("name", safeName);
                    func.put("description", toolDef.getDescription() != null ? toolDef.getDescription() : "");
                    // Parse input_schema from JSON string to Map
                    String schemaStr = toolDef.getInputSchema();
                    if (schemaStr != null && !schemaStr.isBlank()) {
                        try {
                            Object schemaObj = new com.fasterxml.jackson.databind.ObjectMapper().readValue(schemaStr, Map.class);
                            func.put("parameters", schemaObj);
                        } catch (Exception e) {
                            log.warn("Failed to parse tool schema for {}: {}", toolDef.getName(), e.getMessage());
                            func.put("parameters", Map.of("type", "object"));
                        }
                    }
                    Map<String, Object> def = new HashMap<>();
                    def.put("type", "function");
                    def.put("function", func);
                    structuredToolDefs.add(def);
                    // Also build LangChain4j ToolSpecification
                    toolSpecs.add(buildToolSpecification(toolDef));
                }
            }
            if (!structuredToolDefs.isEmpty()) {
                chatConfig = new HashMap<>();
                chatConfig.put("_tools", structuredToolDefs);
            }
        }
        // Create LangChain4j ChatLanguageModel for this agent run
        dev.langchain4j.model.chat.ChatLanguageModel lcModel = null;
        try {
            lcModel = llmService.getChatLanguageModel(model);
        } catch (Exception e) {
            log.warn("Failed to create LangChain4j model for {}, falling back to LlmService: {}", model, e.getMessage());
        }

        // ── 3. Agent loop (tool-calling loop) ──
        StringBuilder fullResponse = new StringBuilder();
        int toolCallCount = 0;
        int iterationCount = 0;
        long totalStartTime = System.currentTimeMillis();
        String lastResponse = null;
        LlmUsage totalUsage = new LlmUsage();
        Set<String> previousToolSignatures = new HashSet<>();
        int identicalCallCount = 0;

        while (toolCallCount < maxToolCalls) {
            if (cancelFlag != null && cancelFlag.get()) {
                log.info("Agent execution cancelled for node {}", node.getId());
                break;
            }
            long iterationStartTime = System.currentTimeMillis();
            iterationCount++;

            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20 + (toolCallCount * 5),
                        "Итерация " + iterationCount + " из " + maxToolCalls);
                webSocketHandler.sendLog(schemaId, "info", "Итерация " + iterationCount, node.getId());
            }

            LlmUsage iterUsage = new LlmUsage();
            // Final copies for lambda capture
            final String capturedModel = model;
            final List<Node.Message> capturedMessages = messages;
            final Map<String, Object> capturedChatConfig = chatConfig;
            LlmResponse iterResp;
            List<Map<String, Object>> toolCalls = new ArrayList<>();

            try {
                boolean useLc4j = lcModel != null && !toolSpecs.isEmpty()
                        && nodeConfig != null && Boolean.TRUE.equals(nodeConfig.get("useLangChain4j"));
                log.info("Agent loop iteration {}: useLc4j={} lcModel={} toolSpecs={}", iterationCount, useLc4j, lcModel != null, toolSpecs.size());
                String finishReason = null;
                String reasoning = null;
                if (useLc4j) {
                    // ── AI SDK-style streaming path with structured tool calls ──
                    String systemText = (!capturedMessages.isEmpty() && "system".equals(capturedMessages.get(0).getRole()))
                            ? capturedMessages.get(0).getContent() : "";
                    String userPrompt = buildUserPrompt(capturedMessages, systemText);
                    Map<String, Object> streamConfig = capturedChatConfig;

                    // Call streaming with tool call extraction
                    StringBuilder streamText = new StringBuilder();
                    StreamingResult streamResult = llmService.streamingChatWithToolCalls(
                            capturedModel, systemText, userPrompt, streamConfig,
                            token -> { streamText.append(token); }
                    );

                    String text = streamResult.text();
                    finishReason = streamResult.finishReason();
                    reasoning = streamResult.reasoning();

                    // Tool calls from streaming result
                    if (streamResult.hasToolCalls()) {
                        toolCalls.addAll(streamResult.toolCalls());
                    }

                    // Fallback: try parsing tool calls from text
                    if (toolCalls.isEmpty() && !text.isBlank()) {
                        toolCalls = toolExecutionService.parseToolCalls(text);
                    }

                    if (text.isBlank() && !toolCalls.isEmpty()) {
                        text = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(toolCalls);
                    }
                    iterResp = LlmResponse.full(text, null, finishReason);
                } else {
                    // ── Legacy LlmService path ──
                    final String capturedSystem = (!capturedMessages.isEmpty() && "system".equals(capturedMessages.get(0).getRole()))
                            ? capturedMessages.get(0).getContent() : null;
                    List<Node.Message> convMessages = capturedMessages.size() > 1 && capturedSystem != null
                            ? capturedMessages.subList(1, capturedMessages.size())
                            : capturedMessages;
                    CompletableFuture<LlmResponse> llmFuture = CompletableFuture.supplyAsync(() ->
                            llmService.chat(capturedModel, capturedSystem,
                                    toolExecutionService.buildMessagesForToolCall(convMessages), capturedChatConfig, iterUsage));
                    int perCallTimeout = 600;
                    if (node != null && node.getData() != null && node.getData().getTimeoutSeconds() != null) {
                        perCallTimeout = Math.min(node.getData().getTimeoutSeconds(), 3600);
                    }
                    iterResp = llmFuture.orTimeout(perCallTimeout, TimeUnit.SECONDS).join();
                    if (toolCalls.isEmpty()) {
                        toolCalls = toolExecutionService.parseToolCalls(iterResp.text());
                    }
                }
            } catch (CompletionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    log.error("LLM agent loop call timed out after 120s at iteration {}", iterationCount);
                }
                throw e;
            } catch (Exception e) {
                log.error("LLM call failed at iteration {}: {}", iterationCount, e.getMessage());
                throw new RuntimeException(e);
            }
            lastResponse = iterResp.text();
            if (iterResp.reasoning() != null) {
                reasoningCapture.capture(schemaId, node.getId(), iterationCount, iterResp.reasoning());
            }
            totalUsage.add(iterUsage);
            messages.add(new Node.Message("assistant", lastResponse));
            fullResponse.append(lastResponse).append("\n");

            String finishReason = iterResp.finishReason();

            // If we already parsed toolCalls from LangChain4j path, skip text parsing
            if (toolCalls.isEmpty()) {
                long iterDuration = System.currentTimeMillis() - iterationStartTime;
                if (webSocketHandler != null) {
                    webSocketHandler.sendIteration(schemaId, node.getId(), iterationCount, iterDuration, 0, 0);
                }
                if ("stop".equals(finishReason)) {
                    log.info("Agent iteration {} finished with finish_reason=stop and no tool calls, breaking", iterationCount);
                    break;
                }
                if ("tool_calls".equals(finishReason)) {
                    log.info("Agent iteration {} finish_reason=tool_calls but parser found no tools; retrying", iterationCount);
                    continue;
                }
                if (iterationCount >= minIterations) {
                    log.info("Agent iteration {} produced no tool calls; minIterations={} reached, breaking", iterationCount, minIterations);
                    break;
                }
                log.info("Agent iteration {} produced no tool calls; below minIterations={}, continuing", iterationCount, minIterations);
                continue;
            }

            // stop with tool calls: execute them before possibly breaking
            if ("stop".equals(finishReason)) {
                log.info("Agent iteration {} finish_reason=stop but has {} tool calls, executing then breaking", iterationCount, toolCalls.size());
            }

            // L07: Deduplicate identical tool calls across iterations
            boolean repeatedTooManyTimes = false;
            for (Map<String, Object> toolCall : toolCalls) {
                String signature = String.valueOf(toolCall.get("name")) + ":" + String.valueOf(toolCall.get("arguments"));
                if (previousToolSignatures.contains(signature)) {
                    if (++identicalCallCount >= 3) {
                        log.warn("Agent repeated identical tool call 3 times, breaking loop: {}", signature);
                        repeatedTooManyTimes = true;
                        break;
                    }
                } else {
                    previousToolSignatures.add(signature);
                }
            }
            if (repeatedTooManyTimes) {
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
                if (cancelFlag != null && cancelFlag.get()) {
                    log.info("Agent execution cancelled during tool call for node {}", node.getId());
                    break;
                }
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

            // Compact message history to prevent unbounded growth
            // Uses Magic Context for summarization when available
            int COMPACT_THRESHOLD = 18;
            int KEEP_RECENT = 5;
            if (messages.size() > COMPACT_THRESHOLD) {
                List<Node.Message> systemMsg = List.of(messages.get(0));
                List<Node.Message> oldMessages = messages.subList(1, messages.size() - KEEP_RECENT);
                List<Node.Message> recentMessages = messages.subList(messages.size() - KEEP_RECENT, messages.size());

                // Summarize old messages via LLM
                String origSystemText = !messages.isEmpty() && "system".equals(messages.get(0).getRole())
                        ? messages.get(0).getContent() : "";
                String compactSummary = compactConversation(schemaId, node.getId(), model, origSystemText, oldMessages);
                if (compactSummary != null && !compactSummary.isBlank()) {
                    // Store in Magic Context if available
                    storeInMagicContext(schemaId, node.getId(), compactSummary);
                    // Add summary as system message
                    messages = new ArrayList<>();
                    messages.addAll(systemMsg);
                    messages.add(new Node.Message("system", "[Context summary of previous conversation turns]\n" + compactSummary));
                    messages.addAll(recentMessages);
                    log.info("Compacted {} old messages into summary ({} chars)", oldMessages.size(), compactSummary.length());
                } else {
                    // Fallback: keep system + last KEEP_RECENT messages
                    List<Node.Message> trimmed = new ArrayList<>();
                    trimmed.addAll(systemMsg);
                    trimmed.addAll(recentMessages);
                    messages = trimmed;
                    log.info("Trimmed messages to {} (compaction produced no summary)", messages.size());
                }
            }

            long iterDuration = System.currentTimeMillis() - iterationStartTime;
            if (webSocketHandler != null) {
                webSocketHandler.sendIteration(schemaId, node.getId(), iterationCount, iterDuration, toolsInThisIteration, 0);
            }

            if (toolCalls.isEmpty() || toolCallCount >= maxToolCalls) {
                if (iterationCount < minIterations && "stop".equals(finishReason)) {
                    log.info("Agent iteration {} finish_reason=stop but below minIterations={}, continuing", iterationCount, minIterations);
                    continue;
                }
                break;
            }
            if (iterationCount < minIterations && "stop".equals(finishReason)) {
                log.info("Agent iteration {} finish_reason=stop but below minIterations={}, continuing for next iteration", iterationCount, minIterations);
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
            List<Map<String, String>> writtenFiles = collectWrittenFiles(schemaId, node.getId());
            if (!writtenFiles.isEmpty()) {
                stateManager.getGeneratedFilesRegistry().put(schemaId + ":" + node.getId(), writtenFiles);
            }
        }

        // ── 4. Post-processing (file changes, build check, FLUTTER deps) ──
        finalResponse = postProcessToolAgent(node, currentSchema, schemaId, finalResponse);

        return finalResponse;
    }

    // ─── Collect written files from stateManager ───

    /**
     * Collects files written during this node's execution from the stateManager's
     * fileChanges registry (populated by ToolHandlerService.handleFileWrite for
     * every file_write operation). This is more reliable than parsing the LLM's
     * text response for generatedFiles JSON.
     */
    private List<Map<String, String>> collectWrittenFiles(String schemaId, String nodeId) {
        Map<String, String> changes = stateManager.getFileChanges(schemaId, nodeId);
        if (changes == null || changes.isEmpty()) return List.of();
        List<Map<String, String>> files = new ArrayList<>();
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            files.add(Map.of("path", entry.getKey(), "action", entry.getValue()));
        }
        return files;
    }

    // ─── Extracted helper: Context assembly ───

    /**
     * Assemble context blocks for the system prompt: Magic Context RAG, mempalace,
     * project context, plan steps, diff review (tool-agent only), and tool instructions.
     * Returns the modified systemPrompt with assembled context appended.
     */
    private String assembleAgentContext(
            Node node, WorkflowSchema currentSchema, String schemaId,
            String prompt, String systemPrompt, String contextBlockText,
            boolean isToolAgent, List<String> enabledTools, String agentType) {
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
                log.warn("Failed to build project context: {}", e.getMessage(), e);
            }
        }

        // Diff review block (tool-agent only)
        if (isToolAgent && node.getData() != null && node.getData().getConfig() != null
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

        // Tool instructions — tool-agent only; choose builder by agent type
        if (isToolAgent && !"doc-agent".equals(agentType)) {
            String toolDefinitions = toolExecutionService.buildToolDefinitions(enabledTools);
            String toolInstructions = toolDefinitions + "\n" + toolExecutionService.buildToolInstructions(enabledTools);
            if (toolInstructions != null && !toolInstructions.isBlank()) {
                ctxBlocks.add(new ContextBlock("toolInstructions", toolInstructions, ContextPriority.HIGH));
            }
        } else if (isToolAgent) {
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
            if (isToolAgent) {
                systemPrompt += "\n\n" + ctxResult.text();
            } else {
                systemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "") + ctxResult.text();
            }
        }

        // WebSocket observability — send context stats
        if (webSocketHandler != null) {
            try {
                String statsJson = buildContextStatsJson(ctxResult);
                webSocketHandler.sendLog(schemaId, "info", "Context budget: " + statsJson, node.getId());
            } catch (Exception ignored) {}
        }

        return systemPrompt;
    }

    // ─── Extracted helper: Planner phase ───

    /**
     * Run the dual-model planner phase: if a planner model is configured in the node,
     * call it asynchronously (60s timeout) and inject the resulting architecture plan
     * as an assistant message into the messages list.
     */
    private void runPlannerPhase(Node node, String schemaId, String prompt,
                                  List<Node.Message> messages) {
        String plannerModelBase = node.getData() != null ? node.getData().getPlannerModel() : null;
        if (plannerModelBase == null && node.getData() != null && node.getData().getConfig() != null) {
            Object pm = node.getData().getConfig().get("plannerModel");
            if (pm instanceof String s) plannerModelBase = s;
        }
        final String plannerModel = plannerModelBase;
        boolean hasPlanner = plannerModel != null && !plannerModel.isBlank();
        if (hasPlanner) {
            java.util.concurrent.CompletableFuture<LlmResponse> future = null;
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
                future = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                    llmService.chat(plannerModel, plannerSysPrompt, plannerPrompt, null, null), plannerExecutor);
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
                if (future != null) future.cancel(true);
                log.warn("Planner arch call timed out (60s)");
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "warn",
                        "Planner arch call timed out after 60s", node.getId());
                }
            } catch (Exception e) {
                log.warn("Planner arch call failed: {}", e.getMessage(), e);
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "warn",
                        "Planner failed: " + e.getMessage(), node.getId());
                }
            }
        }
    }

    // ─── Extracted helper: Post-processing ───

    /**
     * Post-process the agent execution result: append file change summary,
     * run auto-build check, and handle FLUTTER dependency installation.
     */
    private String postProcessToolAgent(Node node, WorkflowSchema schema, String schemaId, String finalResponse) {
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
        }

        // Auto build check — if enabled in stage config, run build_app after agent completes
        Map<String, Object> config = node.getData() != null ? node.getData().getConfig() : null;
        boolean autoBuild = config != null && Boolean.TRUE.equals(config.get("autoBuildCheck"));
        if (autoBuild && schema != null && schema.getTargetPath() != null
                && !schema.getTargetPath().isBlank()) {
            finalResponse += buildAndReport(schema, schemaId, node.getId());
        }

        // For FLUTTER projects, auto-install dependencies and verify build after agent completes
        if (schema != null && "FLUTTER".equals(schema.getAppType())
                && schema.getTargetPath() != null && !schema.getTargetPath().isBlank()) {
            try {
                String targetDir = schema.getTargetPath();
                String[] baseDeps = {"provider", "go_router", "intl", "path_provider", "sqflite", "fl_chart", "http"};
                for (String dep : baseDeps) {
                    ProcessBuilder pb = new ProcessBuilder("flutter", "pub", "add", dep);
                    pb.directory(new java.io.File(targetDir));
                    pb.redirectErrorStream(true);
                    SafeProcess.run(pb, 30, java.util.concurrent.TimeUnit.SECONDS);
                }
                // Run flutter pub get to ensure lockfile is fresh
                ProcessBuilder pb = new ProcessBuilder("flutter", "pub", "get");
                pb.directory(new java.io.File(targetDir));
                pb.redirectErrorStream(true);
                SafeProcess.run(pb, 60, java.util.concurrent.TimeUnit.SECONDS);
                finalResponse += "\n\n[DEPENDENCIES] Auto-installed Flutter packages";

                // Auto-fix Flutter compilation errors using the FixPassOrchestrator
                FixPassOrchestrator.FixPassResult fixResult = fixPassOrchestrator.runFixPass(
                        targetDir, node.getId(), schemaId, schema.getName());
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
                log.warn("Flutter post-processing failed (non-fatal)", e);
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
            ProcessBuilder whichPb = new ProcessBuilder("which", sdkCheck);
            String whichOut = SafeProcess.run(whichPb, 5, TimeUnit.SECONDS);
            if (whichOut == null) {
                return "\n\n[BUILD CHECK] " + sdkCheck + " not found. Required for " + pt.getDisplayName() + " projects.";
            }

            // Run the main build command
            ProcessBuilder buildPb = new ProcessBuilder(buildCmd);
            buildPb.directory(new java.io.File(targetPath));
            String buildOut = SafeProcess.run(buildPb, 300, TimeUnit.SECONDS);
            if (buildOut != null) {
                results.add("✅ " + pt.getDisplayName() + " build succeeded");
            } else {
                results.add("❌ " + pt.getDisplayName() + " build failed: timed out");
            }

            // For Flutter on macOS, also try iOS build (non-blocking)
            if (pt == com.agent.orchestrator.model.ProjectType.FLUTTER
                    && System.getProperty("os.name", "").toLowerCase().contains("mac")) {
                String xcOut = SafeProcess.run(new ProcessBuilder("xcode-select", "-p"), 5, TimeUnit.SECONDS);
                if (xcOut != null) {
                    var iosCmd = java.util.List.of("flutter", "build", "ios", "--no-codesign", "--debug");
                    var iosPb = new ProcessBuilder(iosCmd)
                            .directory(new java.io.File(targetPath));
                    String iosOut = SafeProcess.run(iosPb, 300, TimeUnit.SECONDS);
                    if (iosOut != null) {
                        results.add("✅ iOS build succeeded");
                    } else {
                        results.add("⚠️ iOS build: timed out");
                    }
                } else {
                    results.add("⚠️ iOS build: Xcode not found (skip)");
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

    private String buildUserPrompt(List<Node.Message> messages, String systemText) {
        StringBuilder sb = new StringBuilder();
        for (int i = (systemText == null || systemText.isBlank() ? 0 : 1); i < messages.size(); i++) {
            Node.Message m = messages.get(i);
            if ("user".equals(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(m.getContent());
            }
        }
        return sb.toString();
    }

    /**
     * Compact old conversation messages into a summary using the LLM.
     * Returns the summary text, or null if compaction failed.
     */
    private String compactConversation(String schemaId, String nodeId, String model,
                                        String systemPrompt, List<Node.Message> oldMessages) {
        try {
            StringBuilder conversationText = new StringBuilder();
            for (Node.Message msg : oldMessages) {
                String role = msg.getRole() != null ? msg.getRole() : "unknown";
                String content = msg.getContent() != null ? msg.getContent() : "";
                if (content.length() > 500) content = content.substring(0, 500) + "...";
                conversationText.append("[").append(role).append("]\n").append(content).append("\n\n");
            }

            String compactPrompt = "Summarize the following conversation turns into a brief context paragraph. "
                + "Keep all key facts, decisions, and results. Omit verbatim code blocks.\n\n"
                + conversationText.toString();

            LlmResponse resp = llmService.chat(model, null, compactPrompt, null);
            if (resp != null && resp.text() != null && !resp.text().isBlank()
                    && !resp.text().startsWith("Error:") && !resp.text().startsWith("Provider not found")) {
                return resp.text().trim();
            }
        } catch (Exception e) {
            log.warn("Conversation compaction failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Store a summary in Magic Context for future retrieval.
     */
    private void storeInMagicContext(String schemaId, String nodeId, String summary) {
        if (summary == null || summary.isBlank()) return;
        try {
            if (mcIndexer != null && mcIndexer.isAvailable()) {
                mcIndexer.indexNodeOutput(schemaId, nodeId, "agent", summary,
                        schemaRepository.findById(schemaId) != null
                                ? schemaRepository.findById(schemaId).getName() : "",
                        "compaction-summary");
            }
        } catch (Exception e) {
            log.warn("Failed to store compaction summary in Magic Context: {}", e.getMessage());
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private dev.langchain4j.agent.tool.ToolSpecification buildToolSpecification(com.agent.orchestrator.model.Tool toolDef) {
        dev.langchain4j.agent.tool.ToolSpecification.Builder builder =
                dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name(toolDef.getName())
                        .description(toolDef.getDescription() != null ? toolDef.getDescription() : "");
        String inputSchema = toolDef.getInputSchema();
        if (inputSchema != null && !inputSchema.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode schemaNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(inputSchema);
                JsonNode properties = schemaNode.path("properties");
                JsonNode requiredArr = schemaNode.path("required");
                if (properties.isObject() && !properties.isEmpty()) {
                    dev.langchain4j.model.chat.request.json.JsonObjectSchema.Builder paramsBuilder =
                            dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder();
                    properties.fieldNames().forEachRemaining(name -> {
                        JsonNode prop = properties.get(name);
                        String type = prop.path("type").asText("string");
                        String desc = prop.path("description").asText("");
                        paramsBuilder.addProperty(name, toJsonSchemaElement(type, desc));
                    });
                    if (requiredArr.isArray()) {
                        List<String> required = new ArrayList<>();
                        requiredArr.forEach(n -> required.add(n.asText()));
                        paramsBuilder.required(required);
                    }
                    builder.parameters(paramsBuilder.build());
                }
            } catch (Exception e) {
                log.warn("Failed to parse tool schema for {}: {}", toolDef.getName(), e.getMessage());
            }
        }
        return builder.build();
    }

    private dev.langchain4j.model.chat.request.json.JsonSchemaElement toJsonSchemaElement(String type, String description) {
        switch (type) {
            case "string":
                return description != null && !description.isBlank()
                        ? dev.langchain4j.model.chat.request.json.JsonStringSchema.builder().description(description).build()
                        : dev.langchain4j.model.chat.request.json.JsonStringSchema.builder().build();
            case "integer":
            case "number":
                return description != null && !description.isBlank()
                        ? dev.langchain4j.model.chat.request.json.JsonIntegerSchema.builder().description(description).build()
                        : dev.langchain4j.model.chat.request.json.JsonIntegerSchema.builder().build();
            case "boolean":
                return description != null && !description.isBlank()
                        ? dev.langchain4j.model.chat.request.json.JsonBooleanSchema.builder().description(description).build()
                        : dev.langchain4j.model.chat.request.json.JsonBooleanSchema.builder().build();
            case "array":
                return dev.langchain4j.model.chat.request.json.JsonArraySchema.builder().description(description).build();
            default:
                return dev.langchain4j.model.chat.request.json.JsonStringSchema.builder().description(description).build();
        }
    }
}
