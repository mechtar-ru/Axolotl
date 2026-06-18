package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.agent.orchestrator.model.WorkflowSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.agent.orchestrator.service.ToolCallParser.findMatchingBracket;

/**
 * Strategy for executing verifier-type nodes.
 * Uses ExecutionUtilityService for shared helper methods.
 */
@Component
public class VerifierNodeStrategy implements NodeExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(VerifierNodeStrategy.class);

    private final ExecutionUtilityService utilityService;
    private final AgentNodeStrategy agentStrategy;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final Neo4jSchemaRepository schemaRepository;
    private final ExecutionStateManager stateManager;
    private final ReasoningCapture reasoningCapture;
    private final ObjectMapper objectMapper;

    public VerifierNodeStrategy(ExecutionUtilityService utilityService,
                                AgentNodeStrategy agentStrategy,
                                LlmService llmService,
                                ExecutionWebSocketHandler webSocketHandler,
                                Neo4jSchemaRepository schemaRepository,
                                ExecutionStateManager stateManager,
                                ReasoningCapture reasoningCapture,
                                ObjectMapper objectMapper) {
        this.utilityService = utilityService;
        this.agentStrategy = agentStrategy;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.schemaRepository = schemaRepository;
        this.stateManager = stateManager;
        this.reasoningCapture = reasoningCapture;
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedNodeType() {
        return "verifier";
    }

    @Override
    public Map<String, Object> executeNode(Node node, NodeExecution nodeExec, WorkflowSchema schema,
                                             List<Node> allNodes, List<Edge> edges,
                                             Map<String, Object> executionContext, String schemaId) {
        String resolvedModel = (String) executionContext.getOrDefault("model", "");
        String result = executeVerifierNode(node, schemaId, resolvedModel);
        return Map.of("result", result);
    }

    // ────────────────────────── verifier execution ──────────────────────────

    public String executeVerifierNode(Node node, String schemaId, String resolvedModel) {
        // Collect predecessor results (the generated file content from upstream)
        Map<String, Object> predResults = utilityService.collectPredecessorResults(
                schemaRepository.findById(schemaId), node.getId());
        String inputContent = predResults.values().stream()
                .findFirst().map(Object::toString).orElse("");

        // Extract verification rules from node config
        Map<String, Object> config = node.getData() != null && node.getData().getConfig() != null
                ? node.getData().getConfig() : new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> checks = config.get("checks") instanceof Map
                ? (Map<String, Object>) config.get("checks") : new HashMap<>();

        boolean syntaxCheck = checks.get("syntaxCheck") instanceof Boolean
                ? (Boolean) checks.get("syntaxCheck") : true;
        @SuppressWarnings("unchecked")
        List<String> requiredPatterns = checks.get("requiredPatterns") instanceof List
                ? (List<String>) checks.get("requiredPatterns") : List.of();
        String testCommand = checks.get("testCommand") instanceof String
                ? (String) checks.get("testCommand") : "";
        int maxFileSizeKb = checks.get("maxFileSizeKb") instanceof Number
                ? ((Number) checks.get("maxFileSizeKb")).intValue() : 500;

        boolean rewriteOnFail = config.get("rewriteOnFail") instanceof Boolean
                ? (Boolean) config.get("rewriteOnFail") : false;
        int maxRewriteRetries = config.get("maxRewriteRetries") instanceof Number
                ? ((Number) config.get("maxRewriteRetries")).intValue() : 3;
        boolean stubDetection = config.get("stubDetection") instanceof Boolean
                ? (Boolean) config.get("stubDetection") : true;
        boolean premortemEnabled = checks.get("premortem") instanceof Boolean
                ? (Boolean) checks.get("premortem") : false;
        boolean coverageDesign = config.get("coverageDesign") instanceof Boolean
                ? (Boolean) config.get("coverageDesign") : false;
        boolean coveragePlan = config.get("coveragePlan") instanceof Boolean
                ? (Boolean) config.get("coveragePlan") : false;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10, "Запуск верификации");
            webSocketHandler.sendLog(schemaId, "info", "Верификация: синтаксис=" + syntaxCheck
                    + ", паттерны=" + requiredPatterns + ", тест=" + testCommand
                    + ", rewriteOnFail=" + rewriteOnFail + ", maxRetries=" + maxRewriteRetries
                    + ", premortem=" + premortemEnabled, node.getId());
        }

        String model = resolvedModel;
        if (model == null || model.isBlank()) {
            model = utilityService.resolveModel(node.getData() != null ? node.getData().getModel() : null,
                    null, null, null);
        }

        // ── Step 1: Premortem risk analysis ──
        List<String> premortemPredictions = runPremortemCheck(model, inputContent, schemaId, node.getId(), premortemEnabled);

        // ── Step 2: Design/plan coverage check ──
        List<Map<String, Object>> coverageCheckResults = runCoverageCheck(schemaId, coverageDesign, coveragePlan);

        // ── Step 3: File write verification ──
        List<Map<String, Object>> allCheckResults = new ArrayList<>();
        checkFileWriteCalls(predResults, inputContent, schemaId, node.getId(), allCheckResults);

        // ── Step 4: Detect project type ──
        ProjectInfo projectInfo = detectProjectType(schemaId);

        // ── Step 5: Install Flutter dependencies if needed ──
        runFlutterPubGet(projectInfo.projectType, projectInfo.targetPath);

        // ── Step 6: Main verifier loop ──
        VerifierLoopResult loopResult = runVerifierLoop(model, schemaId, node, resolvedModel,
                inputContent, allCheckResults, premortemPredictions,
                projectInfo.projectType, projectInfo.targetPath,
                syntaxCheck, requiredPatterns, testCommand, stubDetection,
                premortemEnabled, rewriteOnFail, maxRewriteRetries);

        // ── Step 7: Build and return structured result ──
        return buildVerifierResult(loopResult, allCheckResults, premortemPredictions,
                coverageCheckResults, premortemEnabled, maxRewriteRetries, schemaId, node);
    }

    // ────────────────────── Extracted Step Methods ──────────────────────

    /**
     * Step 1: Predict failure scenarios (premortem) if enabled.
     */
    private List<String> runPremortemCheck(String model, String inputContent, String schemaId, String nodeId,
                                            boolean premortemEnabled) {
        List<String> predictions = new ArrayList<>();
        if (!premortemEnabled) return predictions;

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "Premortem: predicting failure scenarios", nodeId);
        }
        String premortemPrompt = "Analyze the following code and predict potential failure scenarios, bugs, or issues. "
                + "List each prediction as a separate line starting with '- '\\n\\nCode:\\n" + inputContent;
        LlmResponse premortemResp = llmService.chat(model, null, premortemPrompt, null);
        String premortemResult = premortemResp.text();
        if (premortemResp.reasoning() != null) {
            reasoningCapture.capture(nodeId, premortemResp.reasoning());
        }
        if (premortemResult != null) {
            for (String line : premortemResult.split("\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- ")) {
                    predictions.add(trimmed.substring(2).trim());
                }
            }
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "info",
                        "Premortem predictions: " + predictions.size(), nodeId);
            }
        }
        return predictions;
    }

    /**
     * Step 2: Check design/plan documents for coverage against generated code.
     */
    private List<Map<String, Object>> runCoverageCheck(String schemaId, boolean coverageDesign, boolean coveragePlan) {
        List<Map<String, Object>> coverageCheckResults = new ArrayList<>();
        if (!coverageDesign && !coveragePlan) return coverageCheckResults;

        WorkflowSchema schema = schemaRepository.findById(schemaId);
        String targetPath = schema != null && schema.getTargetPath() != null ? schema.getTargetPath() : null;
        if (targetPath == null) return coverageCheckResults;

        Path designDir = Path.of(targetPath, "design");
        Path planDir = Path.of(targetPath, "plan");
        Path targetPathObj = Path.of(targetPath);
        StringBuilder coverageContext = new StringBuilder();

        if (coverageDesign && Files.isDirectory(designDir)) {
            try {
                Files.walk(designDir, 2)
                        .filter(p -> p.toString().endsWith(".md"))
                        .forEach(p -> {
                            try {
                                String content = Files.readString(p);
                                String relPath = targetPathObj.relativize(p).toString();
                                coverageContext.append("\n=== DESIGN DOC: ").append(relPath).append(" ===\n");
                                coverageContext.append(content.substring(0, Math.min(content.length(), 3000))).append("\n");
                            } catch (Exception e) {
                                log.warn("Failed to read design doc {}: {}", p, e.getMessage(), e);
                            }
                        });
            } catch (Exception e) {
                log.warn("Failed to walk design dir {}: {}", designDir, e.getMessage(), e);
            }
        }

        if (coveragePlan && planDir != null && Files.isDirectory(planDir)) {
            try {
                Path stepsDir = planDir.resolve("steps");
                if (Files.isDirectory(stepsDir)) {
                    Files.walk(stepsDir, 1)
                            .filter(p -> p.toString().endsWith(".md"))
                            .forEach(p -> {
                                try {
                                    String content = Files.readString(p);
                                    String relPath = planDir.relativize(p).toString();
                                    coverageContext.append("\n=== PLAN STEP: ").append(relPath).append(" ===\n");
                                    coverageContext.append(content.substring(0, Math.min(content.length(), 2000))).append("\n");
                                } catch (Exception e) {
                                    log.warn("Failed to read plan step {}: {}", p, e.getMessage(), e);
                                }
                            });
                }
            } catch (Exception e) {
                log.warn("Failed to walk plan dir {}: {}", planDir, e.getMessage(), e);
            }
        }

        if (!coverageContext.isEmpty()) {
            Map<String, Object> coverageCheck = new HashMap<>();
            coverageCheck.put("name", "coverage");
            coverageCheck.put("hasDesignDocs", Files.isDirectory(designDir));
            coverageCheck.put("hasPlanSteps", planDir != null && Files.isDirectory(planDir.resolve("steps")));
            coverageCheck.put("coverageContext", coverageContext.toString());
            coverageCheck.put("passed", true);
            coverageCheckResults.add(coverageCheck);
        }
        return coverageCheckResults;
    }

    /**
     * Step 3: Check that the upstream agent made at least one file_write call.
     */
    private void checkFileWriteCalls(Map<String, Object> predResults, String inputContent,
                                      String schemaId, String nodeId,
                                      List<Map<String, Object>> allCheckResults) {
        int fileWriteCount = 0;
        if (!predResults.isEmpty()) {
            for (String predNodeId : predResults.keySet()) {
                Map<String, String> changes = stateManager.getFileChanges(schemaId, predNodeId);
                if (changes != null) {
                    fileWriteCount += changes.size();
                }
            }
        }
        if (!predResults.isEmpty() && fileWriteCount == 0 && !inputContent.isBlank()) {
            Map<String, Object> noFileCheck = new HashMap<>();
            noFileCheck.put("name", "file_write_calls");
            noFileCheck.put("passed", false);
            noFileCheck.put("error", "Upstream agent made 0 file_write calls — no files were generated");
            noFileCheck.put("fileCount", 0);
            allCheckResults.add(noFileCheck);
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "warning",
                        "Zero file_write calls detected from upstream agent — no files generated", nodeId);
            }
        }
    }

    /**
     * Inner class holding project type and target path.
     */
    private static class ProjectInfo {
        final String projectType;
        final String targetPath;

        ProjectInfo(String projectType, String targetPath) {
            this.projectType = projectType;
            this.targetPath = targetPath;
        }
    }

    /**
     * Step 4: Determine project type for Flutter-specific checks.
     */
    private ProjectInfo detectProjectType(String schemaId) {
        String projectType = null;
        String targetPath = null;
        try {
            WorkflowSchema ws = schemaRepository.findById(schemaId);
            if (ws != null) {
                projectType = ws.getProjectType();
                targetPath = ws.getTargetPath();
            }
        } catch (Exception e) {
            log.warn("Could not determine project type for schema {}: {}", schemaId, e.getMessage(), e);
        }
        return new ProjectInfo(projectType, targetPath);
    }

    /**
     * Step 5: Install Flutter dependencies if project is FLUTTER type.
     */
    private void runFlutterPubGet(String projectType, String targetPath) {
        if (!"FLUTTER".equals(projectType) || targetPath == null) return;

        try {
            ProcessBuilder pb = new ProcessBuilder("flutter", "pub", "get");
            pb.directory(new java.io.File(targetPath));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                log.debug("flutter pub get completed in {}: exit={}", targetPath, p.exitValue());
            } else {
                p.destroyForcibly();
                log.warn("flutter pub get timed out in {}", targetPath);
            }
        } catch (Exception e) {
            log.warn("Failed to run flutter pub get in {}", targetPath, e);
        }
    }

    /**
     * Inner class holding verifier loop results.
     */
    private static class VerifierLoopResult {
        final String currentResult;
        final int rewriteRetries;
        final boolean allPassed;

        VerifierLoopResult(String currentResult, int rewriteRetries, boolean allPassed) {
            this.currentResult = currentResult;
            this.rewriteRetries = rewriteRetries;
            this.allPassed = allPassed;
        }
    }

    /**
     * Step 6: Main LLM-based verifier loop with optional auto-rewrite on FAIL.
     */
    private VerifierLoopResult runVerifierLoop(String model, String schemaId, Node node, String resolvedModel,
                                                String inputContent, List<Map<String, Object>> allCheckResults,
                                                List<String> premortemPredictions,
                                                String projectType, String targetPath,
                                                boolean syntaxCheck, List<String> requiredPatterns,
                                                String testCommand, boolean stubDetection,
                                                boolean premortemEnabled, boolean rewriteOnFail,
                                                int maxRewriteRetries) {
        int rewriteRetries = 0;
        String currentResult = "";
        String currentContent = inputContent;
        boolean allPassed = false;
        String previousContent = null;
        String previousErrors = null;

        while (rewriteRetries <= maxRewriteRetries && !allPassed) {
            // Build verification prompt for this iteration
            String verificationPrompt = buildVerificationPrompt(currentContent, rewriteRetries, maxRewriteRetries,
                    stubDetection, syntaxCheck, projectType, requiredPatterns, testCommand,
                    premortemEnabled, premortemPredictions);

            // Build a temporary NodeData with verifier-specific settings
            Node.NodeData verifierData = node.getData() != null ? node.getData() : new Node.NodeData();
            verifierData.setAgentType("verifier");
            verifierData.setEnabledTools(List.of("file_read", "bash", "grep"));
            verifierData.setMaxToolCalls(10);
            verifierData.setUserPrompt(verificationPrompt);
            verifierData.setSystemPrompt("Ты — верификатор. Проверяй сгенерированный код и возвращай структурированный JSON с результатами проверок.");
            node.setData(verifierData);
            node.setType("agent"); // Temporarily treat as agent for tool execution

            // Execute via tool agent path
            currentResult = agentStrategy.executeToolAgentNode(node, schemaId, resolvedModel, null);

            // Restore verifier type
            node.setType("verifier");

            // Parse result
            String errors = parseVerifierResult(currentResult, rewriteRetries, allCheckResults);
            boolean iterationPassed = errors == null;

            // L08: Regression guard — roll back if fix made things worse
            if (!iterationPassed && previousErrors != null) {
                int currentErrorCount = countErrorEntries(errors);
                int previousErrorCount = countErrorEntries(previousErrors);
                if (currentErrorCount > previousErrorCount) {
                    log.warn("Fix made things worse ({} -> {} errors), rolling back", previousErrorCount, currentErrorCount);
                    currentContent = previousContent;
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "warning",
                                "Rewrite made things worse, rolling back", node.getId());
                    }
                    break;
                }
            }

            if (iterationPassed) {
                allPassed = true;
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "success",
                            "Верификация PASS" + (rewriteRetries > 0 ? " (after " + rewriteRetries + " rewrite(s))" : ""), node.getId());
                }
                break;
            }

            // If FAIL and rewriteOnFail is enabled, try to fix
            if (rewriteOnFail && rewriteRetries < maxRewriteRetries) {
                rewriteRetries++;
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "warning",
                            "Verification FAIL, attempting rewrite " + rewriteRetries + "/" + maxRewriteRetries, node.getId());
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING",
                            30 + (rewriteRetries * 20), "Rewrite attempt " + rewriteRetries);
                }

                // Save state for regression guard
                previousContent = currentContent;
                previousErrors = errors;
                currentContent = runRewriteFix(model, schemaId, node, currentContent, errors);
            } else {
                break;
            }
        }

        return new VerifierLoopResult(currentResult, rewriteRetries, allPassed);
    }

    /**
     * Build the verification prompt for a given iteration.
     */
    private String buildVerificationPrompt(String currentContent, int rewriteRetries, int maxRewriteRetries,
                                            boolean stubDetection, boolean syntaxCheck, String projectType,
                                            List<String> requiredPatterns, String testCommand,
                                            boolean premortemEnabled, List<String> premortemPredictions) {
        StringBuilder prompt = new StringBuilder();
        String checkTypes = stubDetection ? " на ошибки и наличие заглушек (stubs)" : " на ошибки";
        prompt.append("Ты — верификатор кода. Проверь сгенерированный файл").append(checkTypes).append(".\\n\\n");
        prompt.append("Инструкции:\\n");
        prompt.append("1. Сначала прочитай содержимое файла через file_read\\n");
        if (syntaxCheck) {
            if ("FLUTTER".equals(projectType)) {
                prompt.append("2. Установи зависимости (bash 'flutter pub get'), затем запусти синтаксическую проверку (bash 'dart analyze <filepath>')\\n");
            } else {
                prompt.append("2. Запусти синтаксическую проверку: bash 'python3 -m py_compile <filepath>'\\n");
            }
        }
        if (stubDetection) {
            prompt.append("3. Проверь файл на наличие заглушек (stubs):\\n");
            prompt.append("   - Посчитай строки реального кода (без комментариев, пустых строк и import'ов)\\n");
            prompt.append("   - Если строк кода < 15 для .dart/.py/.java/.ts/.js файла — это заглушка\\n");
            prompt.append("   - Найди '// TODO', '// stub', '// placeholder', '// FIXME' — это заглушки\\n");
            prompt.append("   - Найди пустые тела классов 'class Foo {}' или функций 'void foo() {}'\\n");
            prompt.append("   - Найди 'return null;' в методах, которые должны возвращать данные\\n");
            prompt.append("   - Найди 'throw UnimplementedError()' или 'throw UnsupportedOperationException'\\n");
        }
        if (!requiredPatterns.isEmpty()) {
            prompt.append("4. Проверь наличие обязательных паттернов через bash grep:\\n");
            for (String pattern : requiredPatterns) {
                prompt.append("   - \\\"").append(pattern).append("\\\"\\n");
            }
        }
        if (testCommand != null && !testCommand.isBlank()) {
            prompt.append("5. Запусти тестовую команду: bash '").append(testCommand).append("'\\n");
        } else if ("FLUTTER".equals(projectType)) {
            prompt.append("5. Если есть тестовые файлы (*.dart в test/ или *_test.dart), запусти: bash 'flutter test'\\n");
        }
        if (premortemEnabled && !premortemPredictions.isEmpty()) {
            prompt.append("6. Проверь, какие из предсказанных сценариев отказа подтвердились:\\n");
            for (String pred : premortemPredictions) {
                prompt.append("   - \\\"").append(pred).append("\\\"\\n");
            }
        }
        prompt.append("\\nФормат ответа (строгий JSON, без markdown):\\n");
        prompt.append("{\\n");
        prompt.append("  \\\"status\\\": \\\"PASS\\\" или \\\"FAIL\\\",\\n");
        prompt.append("  \\\"checks\\\": [\\n");
        prompt.append("    {\\\"name\\\": \\\"syntax\\\", \\\"passed\\\": true/false},\\n");
        prompt.append("    {\\\"name\\\": \\\"required_patterns\\\", \\\"passed\\\": true/false, \\\"found\\\": [...], \\\"missing\\\": [...]},\\n");
        prompt.append("    {\\\"name\\\": \\\"test_command\\\", \\\"passed\\\": true/false, \\\"error\\\": \\\"...\\\"}\\n");
        prompt.append("  ],\\n");
        prompt.append("  \\\"summary\\\": \\\"Описание результата\\\"\\n");
        prompt.append("}\\n");
        String content = currentContent;
        if (content != null && content.length() > 4000) {
            content = content.substring(0, 4000) + "\\n[... truncated from " + content.length() + " chars]";
        }
        prompt.append("\\nСодержимое файла для проверки:\\n").append(content);

        if (rewriteRetries > 0) {
            prompt.append("\\n\\n=== Rewrite attempt ").append(rewriteRetries)
                    .append(" of ").append(maxRewriteRetries).append(" ===");
        }
        return prompt.toString();
    }

    /**
     * Parse the verifier agent's JSON result. Returns an error string if checks failed, or null if all passed.
     * Appends check results to allCheckResults.
     */
    private String parseVerifierResult(String currentResult, int rewriteRetries,
                                        List<Map<String, Object>> allCheckResults) {
        if (currentResult == null || currentResult.isBlank()) return "empty result";

        try {
            String jsonStr = currentResult.trim();
            int jsonStart = jsonStr.indexOf('{');
            int jsonEnd = -1;
            if (jsonStart >= 0) {
                jsonEnd = findMatchingBracket(jsonStr, jsonStart) + 1;
            }
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                return "parse error: could not find valid JSON object";
            }
            jsonStr = jsonStr.substring(jsonStart, jsonEnd);

            JsonNode root = objectMapper.readTree(jsonStr);
            String status = root.has("status") ? root.get("status").asText() : "PASS";

            StringBuilder errorsBuilder = new StringBuilder();

            if (root.has("checks")) {
                for (JsonNode check : root.get("checks")) {
                    Map<String, Object> checkMap = new HashMap<>();
                    checkMap.put("name", check.has("name") ? check.get("name").asText() : "unknown");
                    checkMap.put("passed", check.has("passed") ? check.get("passed").asBoolean() : false);
                    checkMap.put("iteration", rewriteRetries);
                    allCheckResults.add(checkMap);

                    if (!check.has("passed") || !check.get("passed").asBoolean()) {
                        String errMsg = check.has("name") ? check.get("name").asText() + " failed" : "check failed";
                        if (check.has("error")) {
                            errMsg += ": " + check.get("error").asText();
                        }
                        errorsBuilder.append(errMsg).append("\\n");
                    }
                }
            }

            if ("PASS".equals(status)) {
                return null; // all passed
            }
            return errorsBuilder.length() > 0 ? errorsBuilder.toString() : "verification FAIL";
        } catch (Exception e) {
            log.warn("Не удалось распарсить результат верификации: {}", e.getMessage(), e);
            return "parse error: " + e.getMessage();
        }
    }

    /**
     * Call LLM to fix code based on verification errors, then write the fix back.
     */
    private String runRewriteFix(String model, String schemaId, Node node, String currentContent, String errors) {
        String fixPrompt = "The following code has issues that need to be fixed.\\n\\n"
                + "Original code:\\n" + currentContent + "\\n\\n"
                + "Errors found:\\n" + errors + "\\n\\n"
                + "Fix the issues and return ONLY the corrected code. Do NOT include any explanations, markdown fences, or extra text.";

        LlmResponse fixedResp = llmService.chat(model, null, fixPrompt, null);
        String fixedCode = fixedResp.text();
        if (fixedResp.reasoning() != null) {
            reasoningCapture.capture(node.getId(), fixedResp.reasoning());
        }
        if (fixedCode == null || fixedCode.isBlank()) return currentContent;

        // Clean up the response (remove markdown fences if present)
        String cleaned = fixedCode.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```\\\\w*\\\\n?", "").replaceFirst("\\\\n?```$", "").trim();
        }

        // Write the fixed content directly to the schema target path
        try {
            WorkflowSchema ws = schemaRepository.findById(schemaId);
            if (ws != null && ws.getTargetPath() != null) {
                String targetPath = ws.getTargetPath();
                // Try to determine relative file path from generated files registry
                String relativeFilePath = "fix_output";
                String registryKey = schemaId + ":" + node.getId();
                String registeredPath = stateManager.getOutputFileRegistry().get(registryKey);
                if (registeredPath != null) {
                    Path registered = Path.of(registeredPath);
                    if (registered.isAbsolute()) {
                        Path target = Path.of(targetPath);
                        try {
                            relativeFilePath = target.relativize(registered).toString();
                        } catch (Exception e) {
                            relativeFilePath = registered.getFileName().toString();
                        }
                    } else {
                        relativeFilePath = registeredPath;
                    }
                }
                java.nio.file.Files.writeString(java.nio.file.Path.of(targetPath, relativeFilePath), cleaned);
                log.info("Wrote fixed file: {}/{}", targetPath, relativeFilePath);
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                            "Fixed code written to: " + targetPath + "/" + relativeFilePath, node.getId());
                }
            }
        } catch (IOException e) {
            log.error("Failed to write fixed file: {}", e.getMessage(), e);
        }
        return cleaned;
    }

    /**
     * Step 7: Build structured JSON result with all check data and handle PASS/FAIL status.
     */
    private String buildVerifierResult(VerifierLoopResult loopResult, List<Map<String, Object>> allCheckResults,
                                        List<String> premortemPredictions,
                                        List<Map<String, Object>> coverageCheckResults,
                                        boolean premortemEnabled, int maxRewriteRetries,
                                        String schemaId, Node node) {
        // Build structured result with premortem predictions and rewrite count
        Map<String, Object> structuredResult = new HashMap<>();
        boolean finalPass = allCheckResults.isEmpty() || allCheckResults.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
        structuredResult.put("status", finalPass ? "PASS" : "FAIL");
        structuredResult.put("premortemPredictions", premortemPredictions);
        structuredResult.put("checkResults", allCheckResults);
        structuredResult.put("rewriteRetries", loopResult.rewriteRetries);
        structuredResult.put("premortemEnabled", premortemEnabled);
        if (!coverageCheckResults.isEmpty()) {
            structuredResult.put("coverage", coverageCheckResults);
        }

        String resultStr;
        try {
            resultStr = objectMapper.writeValueAsString(structuredResult);
        } catch (Exception e) {
            resultStr = loopResult.currentResult != null ? loopResult.currentResult : "{\"status\":\"FAIL\",\"summary\":\"Verification error\"}";
        }

        // If all passed or max retries hit, store the structured result
        if (!finalPass && loopResult.rewriteRetries >= maxRewriteRetries) {
            node.setStatus(Node.NodeStatus.FAILED);
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error",
                        "Верификация FAIL after " + loopResult.rewriteRetries + " rewrite(s)", node.getId());
                webSocketHandler.sendProgress(schemaId, node.getId(), "FAILED", 100,
                        "Failed after " + loopResult.rewriteRetries + " rewrite(s)");
            }
            structuredResult.put("status", "FAILED");
            structuredResult.put("error", "Verification failed after " + (loopResult.rewriteRetries + 1) + " attempts");
            try {
                resultStr = objectMapper.writeValueAsString(structuredResult);
            } catch (Exception e) {
                resultStr = "{\"status\":\"FAILED\",\"error\":\"Verification failed after " + (loopResult.rewriteRetries + 1) + " attempts\"}";
            }
            stateManager.getNodeResults().computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                    .put(node.getId(), resultStr);
            return resultStr;
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "success",
                    "Верификация PASS" + (loopResult.rewriteRetries > 0 ? " after " + loopResult.rewriteRetries + " rewrite(s)" : ""), node.getId());
        }

        return resultStr;
    }

    /**
     * Count the number of error entries in the parseVerifierResult error string.
     * Errors are separated by literal "\n" sequences.
     */
    private static int countErrorEntries(String errors) {
        if (errors == null || errors.isBlank()) return 0;
        int count = 1;
        for (int i = 0; i < errors.length() - 1; i++) {
            if (errors.charAt(i) == '\\' && errors.charAt(i + 1) == 'n') {
                count++;
            }
        }
        return count;
    }
}
