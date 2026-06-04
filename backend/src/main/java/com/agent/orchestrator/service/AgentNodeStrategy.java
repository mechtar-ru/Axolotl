package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.llm.LlmUsage;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Node;
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
public class AgentNodeStrategy {

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

    public AgentNodeStrategy(ExecutionUtilityService utilityService,
                              LlmService llmService,
                              ExecutionWebSocketHandler webSocketHandler,
                              MemPalaceClient memPalaceClient,
                              ToolExecutor toolExecutor,
                              Neo4jSchemaRepository schemaRepository,
                              ProjectContextBuilder projectContextBuilder,
                              ExecutionStateManager stateManager,
                              ReasoningCapture reasoningCapture) {
        this.utilityService = utilityService;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.toolExecutor = toolExecutor;
        this.schemaRepository = schemaRepository;
        this.projectContextBuilder = projectContextBuilder;
        this.stateManager = stateManager;
        this.reasoningCapture = reasoningCapture;
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
        String contextBlock = utilityService.buildContextBlock(predecessorResults);
        if (!contextBlock.isEmpty()) {
            String effectiveSystem = (systemPrompt != null ? systemPrompt + "\n\n" : "")
                    + "Контекст от предыдущих узлов:\n" + contextBlock;
            systemPrompt = effectiveSystem;
        }

        prompt = utilityService.interpolateVariables(prompt, currentSchema, predecessorResults);
        if (systemPrompt != null) {
            systemPrompt = utilityService.interpolateVariables(systemPrompt, currentSchema, predecessorResults);
        }

        if (memPalaceClient.isEnabled()) {
            String memoryContext = memPalaceClient.buildGraphContext(prompt, 5);
            if (!memoryContext.isEmpty()) {
                systemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "") + memoryContext;
            }
        }

        if (currentSchema != null && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
            try {
                String projectContext = projectContextBuilder.buildContext(
                        currentSchema.getTargetPath(), currentSchema.getWorkspaceId(), schemaId);
                if (!projectContext.isEmpty()) {
                    systemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "")
                            + "=== Project Context ===\n" + projectContext;
                }
            } catch (Exception e) {
                log.warn("Failed to build project context: {}", e.getMessage());
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
            Map<String, Object> extracted = utilityService.extractGeneratedFiles(result);
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
        String agentType = node.getData().getAgentType();
        List<String> enabledTools = node.getData().getEnabledTools();
        int maxToolCalls = node.getData().getMaxToolCalls() > 0 ? node.getData().getMaxToolCalls() : 10;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Инициализация агента с инструментами");
            webSocketHandler.sendLog(schemaId, "info", "Агент типа: " + agentType + ", инструменты: " + enabledTools, node.getId());
        }

        String prompt = node.getData().getUserPrompt();
        String systemPrompt = node.getData().getSystemPrompt();

        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
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

        String toolDefs = utilityService.buildToolDefinitions(enabledTools);
        String toolInstructions = utilityService.buildToolInstructions(enabledTools);
        systemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "") + toolInstructions;

        if (!contextBlock.isEmpty()) {
            systemPrompt += "\n\nКонтекст от предыдущих узлов:\n" + contextBlock;
        }

        if (memPalaceClient.isEnabled()) {
            String memoryContext = memPalaceClient.buildGraphContext(prompt, 5);
            if (!memoryContext.isEmpty()) {
                systemPrompt += "\n\n" + memoryContext;
            }
        }

        if (currentSchema != null && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
            try {
                String projectContext = projectContextBuilder.buildContext(
                        currentSchema.getTargetPath(), currentSchema.getWorkspaceId(), schemaId);
                if (!projectContext.isEmpty()) {
                    systemPrompt += "\n\n=== Project Context ===\n" + projectContext;
                }
            } catch (Exception e) {
                log.warn("Failed to build project context: {}", e.getMessage());
            }
        }

        // If requireDiffReview is enabled, inject instructions to modify existing files
        if (node.getData() != null && node.getData().getConfig() != null
                && Boolean.TRUE.equals(node.getData().getConfig().get("requireDiffReview"))) {
            systemPrompt += "\n\nIMPORTANT: Diff review mode enabled.\n"
                + "You MUST modify existing files using file_write on their current paths.\n"
                + "Do NOT create new files unless absolutely necessary.\n"
                + "After you finish, the pipeline will pause for human diff review.\n"
                + "Always read the existing file content first before modifying it.\n";
        }

        List<Node.Message> messages = new ArrayList<>();
        messages.add(new Node.Message("system", systemPrompt));
        messages.add(new Node.Message("user", prompt));

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
                    utilityService.buildMessagesForToolCall(messages), chatConfig, iterUsage);
            lastResponse = iterResp.text();
            if (iterResp.reasoning() != null) {
                reasoningCapture.capture(node.getId(), iterResp.reasoning());
            }
            totalUsage.add(iterUsage);
            messages.add(new Node.Message("assistant", lastResponse));
            fullResponse.append(lastResponse).append("\n");

            List<Map<String, Object>> toolCalls = utilityService.parseToolCalls(lastResponse);
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
                    utilityService.sendUserApprovalRequest(schemaId, node.getId(), toolCallCount, maxToolCalls);
                    break;
                }

                String toolId = (String) toolCall.get("name");
                @SuppressWarnings("unchecked")
                Map<String, Object> args = (Map<String, Object>) toolCall.get("arguments");

                String targetPath = currentSchema != null ? currentSchema.getTargetPath() : null;
                String projectType = currentSchema != null ? currentSchema.getProjectType() : null;
                String toolResult = utilityService.executeToolCall(toolId, args, node, schemaId, targetPath, projectType);
                messages.add(new Node.Message("tool", toolResult));
                messages.add(new Node.Message("tool_call_id", (String) toolCall.get("id")));

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
            Map<String, Object> extracted = utilityService.extractGeneratedFiles(finalResponse);
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

        return finalResponse;
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
}
