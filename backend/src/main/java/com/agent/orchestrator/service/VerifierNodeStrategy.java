package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.agent.orchestrator.model.WorkflowSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strategy for executing verifier-type nodes.
 * Uses ExecutionUtilityService for shared helper methods.
 */
@Component
public class VerifierNodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(VerifierNodeStrategy.class);

    private final ExecutionUtilityService utilityService;
    private final AgentNodeStrategy agentStrategy;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final Neo4jSchemaRepository schemaRepository;
    private final ExecutionStateManager stateManager;
    private final ReasoningCapture reasoningCapture;

    public VerifierNodeStrategy(ExecutionUtilityService utilityService,
                                AgentNodeStrategy agentStrategy,
                                LlmService llmService,
                                ExecutionWebSocketHandler webSocketHandler,
                                Neo4jSchemaRepository schemaRepository,
                                ExecutionStateManager stateManager,
                                ReasoningCapture reasoningCapture) {
        this.utilityService = utilityService;
        this.agentStrategy = agentStrategy;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.schemaRepository = schemaRepository;
        this.stateManager = stateManager;
        this.reasoningCapture = reasoningCapture;
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

        // New config: rewrite on fail
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

        // Step 1: Premortem predictions (if enabled)
        List<String> premortemPredictions = new ArrayList<>();
        if (premortemEnabled) {
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "info", "Premortem: predicting failure scenarios", node.getId());
            }
            String premortemPrompt = "Analyze the following code and predict potential failure scenarios, bugs, or issues. "
                    + "List each prediction as a separate line starting with '- '\\n\\nCode:\\n" + inputContent;
            LlmResponse premortemResp = llmService.chat(model, null, premortemPrompt, null);
            String premortemResult = premortemResp.text();
            if (premortemResp.reasoning() != null) {
                reasoningCapture.capture(node.getId(), premortemResp.reasoning());
            }
            if (premortemResult != null) {
                for (String line : premortemResult.split("\\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("- ")) {
                        premortemPredictions.add(trimmed.substring(2).trim());
                    }
                }
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "info",
                            "Premortem predictions: " + premortemPredictions.size(), node.getId());
                }
            }
        }

        // Step 1b: Design coverage check (reads design docs and checks against code)
        List<Map<String, Object>> coverageCheckResults = new ArrayList<>();
        if (coverageDesign || coveragePlan) {
            WorkflowSchema schema = schemaRepository.findById(schemaId);
            String targetPath = schema != null && schema.getTargetPath() != null ? schema.getTargetPath() : null;
            if (targetPath != null) {
                Path designDir = Path.of(targetPath, "design");
                Path planDir = Path.of(targetPath, "plan");
                StringBuilder coverageContext = new StringBuilder();

                Path targetPathObj = Path.of(targetPath);
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
                                    } catch (Exception ignored) {}
                                });
                    } catch (Exception ignored) {}
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
                                        } catch (Exception ignored) {}
                                    });
                        }
                    } catch (Exception ignored) {}
                }

                if (!coverageContext.isEmpty()) {
                    Map<String, Object> coverageCheck = new HashMap<>();
                    coverageCheck.put("name", "coverage");
                    coverageCheck.put("hasDesignDocs", Files.isDirectory(designDir));
                    coverageCheck.put("hasPlanSteps", planDir != null && Files.isDirectory(planDir.resolve("steps")));
                    coverageCheck.put("coverageContext", coverageContext.toString());
                    coverageCheck.put("passed", true); // informational by default
                    coverageCheckResults.add(coverageCheck);
                }
            }
        }

        // Steps 2-6: Run checks with optional rewrite loop
        List<Map<String, Object>> allCheckResults = new ArrayList<>();

        // Step 2a: Check that upstream agent made at least one file_write call
        // If predecessor results exist but zero files were written, add a pre-built check
        int fileWriteCount = 0;
        Map<String, Object> noFileCheck = new HashMap<>();
        if (!predResults.isEmpty()) {
            for (String predNodeId : predResults.keySet()) {
                Map<String, String> changes = stateManager.getFileChanges(schemaId, predNodeId);
                if (changes != null) {
                    fileWriteCount += changes.size();
                }
            }
        }
        if (!predResults.isEmpty() && fileWriteCount == 0 && !inputContent.isBlank()) {
            noFileCheck.put("name", "file_write_calls");
            noFileCheck.put("passed", false);
            noFileCheck.put("error", "Upstream agent made 0 file_write calls — no files were generated");
            noFileCheck.put("fileCount", 0);
            allCheckResults.add(noFileCheck);
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "warning",
                        "Zero file_write calls detected from upstream agent — no files generated", node.getId());
            }
        }

        int rewriteRetries = 0;
        String currentContent = inputContent;
        String currentResult = "";

        while (rewriteRetries <= maxRewriteRetries) {
            // Build verification prompt for this iteration
            StringBuilder verificationPrompt = new StringBuilder();
            String checkTypes = stubDetection ? " на ошибки и наличие заглушек (stubs)" : " на ошибки";
            verificationPrompt.append("Ты — верификатор кода. Проверь сгенерированный файл").append(checkTypes).append(".\\n\\n");
            verificationPrompt.append("Инструкции:\\n");
            verificationPrompt.append("1. Сначала прочитай содержимое файла через file_read\\n");
            if (syntaxCheck) {
                verificationPrompt.append("2. Запусти синтаксическую проверку: bash 'python3 -m py_compile <filepath>'\\n");
            }
            if (stubDetection) {
                verificationPrompt.append("3. Проверь файл на наличие заглушек (stubs):\\n");
                verificationPrompt.append("   - Посчитай строки реального кода (без комментариев, пустых строк и import'ов)\\n");
                verificationPrompt.append("   - Если строк кода < 15 для .dart/.py/.java/.ts/.js файла — это заглушка\\n");
                verificationPrompt.append("   - Найди '// TODO', '// stub', '// placeholder', '// FIXME' — это заглушки\\n");
                verificationPrompt.append("   - Найди пустые тела классов 'class Foo {}' или функций 'void foo() {}'\\n");
                verificationPrompt.append("   - Найди 'return null;' в методах, которые должны возвращать данные\\n");
                verificationPrompt.append("   - Найди 'throw UnimplementedError()' или 'throw UnsupportedOperationException'\\n");
            }
            if (!requiredPatterns.isEmpty()) {
                verificationPrompt.append("4. Проверь наличие обязательных паттернов через bash grep:\\n");
                for (String pattern : requiredPatterns) {
                    verificationPrompt.append("   - \\\"").append(pattern).append("\\\"\\n");
                }
            }
            if (testCommand != null && !testCommand.isBlank()) {
                verificationPrompt.append("5. Запусти тестовую команду: bash '").append(testCommand).append("'\\n");
            }
            if (premortemEnabled && !premortemPredictions.isEmpty()) {
                verificationPrompt.append("6. Проверь, какие из предсказанных сценариев отказа подтвердились:\\n");
                for (String pred : premortemPredictions) {
                    verificationPrompt.append("   - \\\"").append(pred).append("\\\"\\n");
                }
            }
            verificationPrompt.append("\\nФормат ответа (строгий JSON, без markdown):\\n");
            verificationPrompt.append("{\\n");
            verificationPrompt.append("  \\\"status\\\": \\\"PASS\\\" или \\\"FAIL\\\",\\n");
            verificationPrompt.append("  \\\"checks\\\": [\\n");
            verificationPrompt.append("    {\\\"name\\\": \\\"syntax\\\", \\\"passed\\\": true/false},\\n");
            verificationPrompt.append("    {\\\"name\\\": \\\"required_patterns\\\", \\\"passed\\\": true/false, \\\"found\\\": [...], \\\"missing\\\": [...]},\\n");
            verificationPrompt.append("    {\\\"name\\\": \\\"test_command\\\", \\\"passed\\\": true/false, \\\"error\\\": \\\"...\\\"}\\n");
            verificationPrompt.append("  ],\\n");
            verificationPrompt.append("  \\\"summary\\\": \\\"Описание результата\\\"\\n");
            verificationPrompt.append("}\\n");
            verificationPrompt.append("\\nСодержимое файла для проверки:\\n").append(currentContent);

            if (rewriteRetries > 0) {
                verificationPrompt.append("\\n\\n=== Rewrite attempt ").append(rewriteRetries)
                        .append(" of ").append(maxRewriteRetries).append(" ===");
            }

            // Build a temporary NodeData with verifier-specific settings
            Node.NodeData verifierData = node.getData() != null ? node.getData() : new Node.NodeData();
            verifierData.setAgentType("verifier");
            verifierData.setEnabledTools(List.of("file_read", "bash", "grep"));
            verifierData.setMaxToolCalls(10);
            verifierData.setUserPrompt(verificationPrompt.toString());
            verifierData.setSystemPrompt("Ты — верификатор. Проверяй сгенерированный код и возвращай структурированный JSON с результатами проверок.");
            node.setData(verifierData);
            node.setType("agent"); // Temporarily treat as agent for tool execution

            // Execute via tool agent path
            currentResult = agentStrategy.executeToolAgentNode(node, schemaId, resolvedModel);

            // Restore verifier type
            node.setType("verifier");

            // Parse result
            boolean allPassed = false;
            StringBuilder errorsBuilder = new StringBuilder();

            if (currentResult != null && !currentResult.isBlank()) {
                try {
                    String jsonStr = currentResult.trim();
                    int jsonStart = jsonStr.indexOf('{');
                    int jsonEnd = jsonStr.lastIndexOf('}');
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        jsonStr = jsonStr.substring(jsonStart, jsonEnd + 1);
                    }

                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(jsonStr);
                    String status = root.has("status") ? root.get("status").asText() : "PASS";
                    String summary = root.has("summary") ? root.get("summary").asText() : "";

                    // Store check results
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
                        allPassed = true;
                        if (webSocketHandler != null) {
                            webSocketHandler.sendLog(schemaId, "success",
                                    "Верификация PASS: " + summary + (rewriteRetries > 0 ? " (after " + rewriteRetries + " rewrite(s))" : ""), node.getId());
                        }
                        break;
                    }
                } catch (Exception e) {
                    log.warn("Не удалось распарсить результат верификации: {}", e.getMessage());
                }
            }

            // If FAIL and rewriteOnFail is enabled, try to fix
            if (!allPassed && rewriteOnFail && rewriteRetries < maxRewriteRetries) {
                rewriteRetries++;
                if (webSocketHandler != null) {
                    webSocketHandler.sendLog(schemaId, "warning",
                            "Verification FAIL, attempting rewrite " + rewriteRetries + "/" + maxRewriteRetries, node.getId());
                    webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING",
                            30 + (rewriteRetries * 20), "Rewrite attempt " + rewriteRetries);
                }

                // Call LLM to fix the code
                String fixPrompt = "The following code has issues that need to be fixed.\\n\\n"
                        + "Original code:\\n" + currentContent + "\\n\\n"
                        + "Errors found:\\n" + errorsBuilder.toString() + "\\n\\n"
                        + "Fix the issues and return ONLY the corrected code. Do NOT include any explanations, markdown fences, or extra text.";

                LlmResponse fixedResp = llmService.chat(model, null, fixPrompt, null);
                String fixedCode = fixedResp.text();
                if (fixedResp.reasoning() != null) {
                    reasoningCapture.capture(node.getId(), fixedResp.reasoning());
                }
                if (fixedCode != null && !fixedCode.isBlank()) {
                    // Clean up the response (remove markdown fences if present)
                    String cleaned = fixedCode.trim();
                    if (cleaned.startsWith("```")) {
                        cleaned = cleaned.replaceFirst("^```\\\\w*\\\\n?", "").replaceFirst("\\\\n?```$", "").trim();
                    }
                    currentContent = cleaned;

                    // Write the fixed code back (if there's a file path in the registry)
                    String prefix = schemaId + ":";
                    for (Map.Entry<String, String> entry : stateManager.getOutputFileRegistry().entrySet()) {
                        if (entry.getKey().startsWith(prefix) && entry.getKey().endsWith(":" + node.getId())) {
                            try {
                                Files.writeString(Path.of(entry.getValue()), cleaned);
                                if (webSocketHandler != null) {
                                    webSocketHandler.sendLog(schemaId, "info",
                                            "Fixed code written to: " + entry.getValue(), node.getId());
                                }
                            } catch (Exception e) {
                                log.warn("Failed to write fixed code: {}", e.getMessage());
                            }
                        }
                    }
                }
            } else if (!allPassed) {
                break;
            }
        }

        // Build structured result with premortem predictions and rewrite count
        Map<String, Object> structuredResult = new HashMap<>();
        structuredResult.put("status", allCheckResults.isEmpty() || allCheckResults.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed"))) ? "PASS" : "FAIL");
        structuredResult.put("premortemPredictions", premortemPredictions);
        structuredResult.put("checkResults", allCheckResults);
        structuredResult.put("rewriteRetries", rewriteRetries);
        structuredResult.put("premortemEnabled", premortemEnabled);
        if (!coverageCheckResults.isEmpty()) {
            structuredResult.put("coverage", coverageCheckResults);
        }

        ObjectMapper mapper = new ObjectMapper();
        String resultStr;
        try {
            resultStr = mapper.writeValueAsString(structuredResult);
        } catch (Exception e) {
            resultStr = currentResult != null ? currentResult : "{\"status\":\"FAIL\",\"summary\":\"Verification error\"}";
        }

        // If all passed or max retries hit, store the structured result
        boolean finalPass = allCheckResults.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
        if (!finalPass && rewriteRetries >= maxRewriteRetries) {
            node.setStatus(Node.NodeStatus.FAILED);
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error",
                        "Верификация FAIL after " + rewriteRetries + " rewrite(s)", node.getId());
                webSocketHandler.sendProgress(schemaId, node.getId(), "FAILED", 100,
                        "Failed after " + rewriteRetries + " rewrite(s)");
            }
            stateManager.getNodeResults().computeIfAbsent(schemaId, k -> new ConcurrentHashMap<>())
                    .put(node.getId(), resultStr);
            return resultStr;
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "success",
                    "Верификация PASS" + (rewriteRetries > 0 ? " after " + rewriteRetries + " rewrite(s)" : ""), node.getId());
        }

        return resultStr;
    }
}
