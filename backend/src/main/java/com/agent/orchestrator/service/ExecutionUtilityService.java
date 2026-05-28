package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utility service for the execution engine.
 * Contains general-purpose helper methods extracted from NodeExecutor
 * to break circular setNodeExecutor dependencies.
 */
@Service
public class ExecutionUtilityService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionUtilityService.class);

    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final ToolExecutor toolExecutor;
    private final Neo4jSchemaRepository schemaRepository;
    private final ProjectContextBuilder projectContextBuilder;
    private final ExecutionStateManager stateManager;
    private final ExecutionRepository executionRepository;

    @Value("${axolotl.sandbox.allowedWriteDirs:.}")
    private java.util.List<String> allowedWriteDirs;

    private static final int MAX_CONTEXT_CHARS = 4000;

    private static final java.util.Set<String> BLOCKED_COMMAND_PATTERNS = java.util.Set.of(
            "rm -rf /", "mkfs", "dd if=", ":(){ :|:&", "> /dev/sd", "format ", "del /f /s /q c:",
            "shutdown", "reboot", "init 0", "init 6", "halt", "poweroff");

    public ExecutionUtilityService(LlmService llmService,
                                   ExecutionWebSocketHandler webSocketHandler,
                                   MemPalaceClient memPalaceClient,
                                   ToolExecutor toolExecutor,
                                   Neo4jSchemaRepository schemaRepository,
                                   ProjectContextBuilder projectContextBuilder,
                                   ExecutionStateManager stateManager,
                                   ExecutionRepository executionRepository) {
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.toolExecutor = toolExecutor;
        this.schemaRepository = schemaRepository;
        this.projectContextBuilder = projectContextBuilder;
        this.stateManager = stateManager;
        this.executionRepository = executionRepository;
    }

    // ────────────────────────── model resolution ──────────────────────────

    /**
     * Simplified model resolution — takes pre-fetched values.
     * Falls back: nodeModel -> schemaModel -> userId -> globalModel -> null.
     */
    public String resolveModel(String nodeModel, String schemaModel, String userId, String globalModel) {
        if (nodeModel != null && !nodeModel.isBlank()) return nodeModel;
        if (schemaModel != null && !schemaModel.isBlank()) return schemaModel;
        if (globalModel != null && !globalModel.isBlank()) return globalModel;
        return null;
    }

    // ────────────────────────── predecessor results ──────────────────────────

    public Map<String, Object> collectPredecessorResults(WorkflowSchema schema, String nodeId) {
        Map<String, Object> results = new HashMap<>();
        if (schema == null || schema.getEdges() == null || schema.getNodes() == null) {
            return results;
        }
        Map<String, String> cached = stateManager.getNodeResults().getOrDefault(schema.getId(), Map.of());

        for (Edge edge : schema.getEdges()) {
            if (nodeId.equals(edge.getTarget())) {
                String sourceId = edge.getSource();
                String result = cached.get(sourceId);
                if (result == null) {
                    for (Node n : schema.getNodes()) {
                        if (sourceId.equals(n.getId()) && n.getData() != null && n.getData().getResult() != null) {
                            result = n.getData().getResult();
                            break;
                        }
                    }
                }
                if (result != null) {
                    String name = schema.getNodes().stream()
                            .filter(n -> sourceId.equals(n.getId()))
                            .map(Node::getName)
                            .findFirst().orElse(sourceId);
                    results.put(name.replaceAll("\\s+", "_"), result);
                }
            }
        }
        return results;
    }

    // ────────────────────────── condition evaluation ──────────────────────────

    public boolean evaluateCondition(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        try (Context ctx = Context.newBuilder("js")
                .allowIO(false)
                .allowCreateProcess(false)
                .allowHostAccess(HostAccess.NONE)
                .build()) {
            org.graalvm.polyglot.Value bindings = ctx.getBindings("js");
            context.forEach(bindings::putMember);
            org.graalvm.polyglot.Value result = ctx.eval("js", "Boolean(" + expression + ")");
            return result.asBoolean();
        } catch (Exception e) {
            log.error("Ошибка вычисления условия '{}': {}", expression, e.getMessage());
            return false;
        }
    }

    // ────────────────────────── variable interpolation ──────────────────────────

    public String interpolateVariables(String text, WorkflowSchema schema, Map<String, Object> predecessorResults) {
        if (text == null || !text.contains("{{")) return text;

        String input = predecessorResults.values().stream().findFirst().map(Object::toString).orElse("");
        text = text.replace("{{input}}", input);

        String prevResult = predecessorResults.values().stream()
                .reduce((first, second) -> second).map(Object::toString).orElse("");
        text = text.replace("{{prev_result}}", prevResult);

        // {{sourceData}} — resolve from the first source-type node's sourceData field
        if (text.contains("{{sourceData}}")) {
            String sourceData = resolveSourceData(schema);
            text = text.replace("{{sourceData}}", sourceData != null ? sourceData : "");
        }

        if (text.contains("{{node:")) {
            if (schema.getNodes() != null) {
                for (Node n : schema.getNodes()) {
                    String result = n.getData() != null && n.getData().getResult() != null ? n.getData().getResult() : "";
                    String key = "{{node:" + n.getName() + "}}";
                    text = text.replace(key, result);
                }
            }
        }

        text = text.replace("{{schema_name}}", schema.getName() != null ? schema.getName() : "");

        return text;
    }

    private String resolveSourceData(WorkflowSchema schema) {
        if (schema == null || schema.getNodes() == null) return "";
        for (Node n : schema.getNodes()) {
            if ("source".equals(n.getType()) && n.getData() != null) {
                log.info("resolveSourceData: source node id={}, name={}, hasSourceData={}, hasConfig={}, hasResult={}",
                        n.getId(), n.getName(),
                        n.getData().getSourceData() != null && !n.getData().getSourceData().isEmpty(),
                        n.getData().getConfig() != null,
                        n.getData().getResult() != null && !n.getData().getResult().isEmpty());

                String sd = n.getData().getSourceData();
                if (sd == null || sd.isEmpty()) {
                    Object cfgSd = n.getData().getConfig() != null
                            ? n.getData().getConfig().get("sourceData") : null;
                    if (cfgSd instanceof String) sd = (String) cfgSd;
                }
                if (sd != null && !sd.isEmpty()) return sd;

                // Check stateManager (populated by NodeRouter during pipeline execution)
                if (stateManager != null && schema.getId() != null) {
                    Map<String, String> nodeResults = stateManager.getNodeResults().get(schema.getId());
                    if (nodeResults != null) {
                        String cachedResult = nodeResults.get(n.getId());
                        log.info("resolveSourceData: stateManager keys={}, cachedResult for '{}' present={}",
                                nodeResults.keySet(), n.getId(), cachedResult != null && !cachedResult.isEmpty());
                        if (cachedResult != null && !cachedResult.isEmpty()) return cachedResult;
                    } else {
                        log.info("resolveSourceData: no nodeResults for schema {}", schema.getId());
                    }
                }

                if (n.getData().getResult() != null && !n.getData().getResult().isEmpty()) {
                    return n.getData().getResult();
                }
            }
        }
        log.info("resolveSourceData: no source node found or all sources empty");
        return "";
    }

    // ────────────────────────── context building ──────────────────────────

    public String buildContextBlock(Map<String, Object> predecessorResults) {
        if (predecessorResults.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        predecessorResults.forEach((name, value) -> {
            sb.append("[").append(name).append("]: ").append(value).append("\n");
        });
        String context = sb.toString().trim();

        if (context.length() > MAX_CONTEXT_CHARS) {
            log.info("Сжатие контекста: {} символов → суммаризация", context.length());
            try {
                String summary = llmService.chat("ollama",
                        "Ты компрессор контекста. Сжато передай суть, сохранив ключевые факты, числа, имена.",
                        "Сожми следующий контекст, сохранив ключевые факты:\n\n" + context,
                        null).text();
                return "[СЖАТЫЙ КОНТЕКСТ]:\n" + summary;
            } catch (Exception e) {
                return context.substring(0, MAX_CONTEXT_CHARS) + "\n... [контекст обрезан]";
            }
        }

        return context;
    }

    // ────────────────────────── sleep with cancel ──────────────────────────

    public boolean sleepWithCancel(long millis, AtomicBoolean cancelFlag) {
        if (cancelFlag.get()) {
            return false;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !cancelFlag.get();
        }
        return !cancelFlag.get();
    }

    // ────────────────────────── generatedFiles extraction ──────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> extractGeneratedFiles(String response) {
        if (response == null || response.isBlank()) return null;
        String tail = response.substring(Math.max(0, response.length() - 500));
        int idx = tail.lastIndexOf("\"generatedFiles\"");
        if (idx < 0) return null;
        int brace = tail.lastIndexOf('{', idx);
        if (brace < 0) return null;
        String candidate = tail.substring(brace);
        int depth = 0;
        int close = -1;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { close = i; break; }
            }
        }
        if (close < 0) return null;
        String json = candidate.substring(0, close + 1);
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse generatedFiles JSON from agent response: {}", e.getMessage());
            return null;
        }
    }

    // ────────────────────────── tool helpers ──────────────────────────

    public String buildToolDefinitions(List<String> toolIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("## available_tools\n\n");
        sb.append("namespace functions {\n\n");
        for (String toolId : toolIds) {
            Tool tool = toolExecutor.getTool(toolId);
            if (tool != null) {
                sb.append("// ").append(tool.getDescription()).append("\n");
                sb.append("type ").append(toolId).append(" = (_: {\n");
                sb.append(tool.getInputSchema().replace("\"", "'")).append("\n}) => any;\n\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    public String buildToolInstructions(List<String> toolIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to tools. To use a tool, respond with a JSON object in your final answer.\n\n");
        sb.append("Available tools:\n");
        for (String toolId : toolIds) {
            Tool tool = toolExecutor.getTool(toolId);
            if (tool != null) {
                sb.append("- ").append(toolId).append(": ").append(tool.getDescription()).append("\n");
            }
        }
        if (toolIds.contains("file_write")) {
            sb.append("\nNote: After each file_write, a syntax validator runs automatically. ");
            sb.append("If errors are found, they appear after the write confirmation. ");
            sb.append("Read them carefully and fix the issues in your next iteration.\n");
        }
        if (toolIds.contains("build_app")) {
            sb.append("\nNote: Use build_app after all files are written to check build dependencies ");
            sb.append("and compile the app. It detects missing SDKs and runs the build.\n");
        }
        sb.append("\nTo call a tool, include tool_calls in your response:\n");
        sb.append("```json\n");
        sb.append("{\"role\": \"assistant\", \"content\": \"...\", \"tool_calls\": [");
        sb.append("{\"id\": \"call_1\", \"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}]");
        sb.append("}\n```\n");
        return sb.toString();
    }

    public String buildMessagesForToolCall(List<Node.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Node.Message msg : messages) {
            sb.append("<message role=\"").append(msg.getRole()).append("\">\n");
            sb.append(msg.getContent()).append("\n</message>\n");
        }
        return sb.toString();
    }

    public List<Map<String, Object>> parseToolCalls(String response) {
        List<Map<String, Object>> calls = new ArrayList<>();
        if (response == null || !response.contains("tool_calls")) {
            return calls;
        }

        try {
            String json = response;
            if (json.contains("```")) {
                json = json.replaceAll("```json\\s*", "");
                json = json.replaceAll("```\\s*", "");
                json = json.trim();
            }

            int toolCallsIdx = json.indexOf("\"tool_calls\"");
            if (toolCallsIdx < 0) {
                toolCallsIdx = json.indexOf("tool_calls");
            }
            if (toolCallsIdx < 0) return calls;

            int arrayStart = json.indexOf("[", toolCallsIdx);
            if (arrayStart < 0) return calls;

            int arrayEnd = findMatchingBracket(json, arrayStart);
            if (arrayEnd < 0) return calls;

            String toolsJson = json.substring(arrayStart, arrayEnd + 1);

            ObjectMapper mapper = new ObjectMapper();
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = mapper.readValue(toolsJson, List.class);
                calls.addAll(parsed);
                return calls;
            } catch (Exception e) {
                log.debug("Strict JSON parse failed: {}", e.getMessage());
            }

            try {
                ObjectMapper lenientMapper = new ObjectMapper()
                        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                        .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
                        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = lenientMapper.readValue(toolsJson, List.class);
                calls.addAll(parsed);
                return calls;
            } catch (Exception e) {
                log.debug("Lenient JSON parse failed: {}", e.getMessage());
            }

            ObjectMapper lenientMapper = new ObjectMapper()
                    .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                    .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
                    .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

            List<String> toolCallObjects = extractTopLevelObjects(toolsJson);
            for (String obj : toolCallObjects) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = lenientMapper.readValue(obj, Map.class);
                    calls.add(parsed);
                } catch (Exception e) {
                    log.debug("Failed to parse individual tool call, trying regex fallback: {}", e.getMessage());
                    Map<String, Object> fallbackCall = extractToolCallWithRegex(obj);
                    if (fallbackCall != null && fallbackCall.get("name") != null) {
                        fallbackCall.putIfAbsent("id", "call_" + calls.size());
                        calls.add(fallbackCall);
                    }
                }
            }

            if (calls.isEmpty()) {
                String diag = toolsJson.length() > 200 ? toolsJson.substring(0, 200) + "..." : toolsJson;
                log.warn("All tool call parsing fallbacks exhausted. Response snippet: {}", diag);
            }

        } catch (Exception e) {
            log.warn("Failed to parse tool calls: {}", e.getMessage());
        }
        return calls;
    }

    private int findMatchingBracket(String json, int startIdx) {
        char openBracket = json.charAt(startIdx);
        char closeBracket = (openBracket == '[') ? ']' : '}';
        int depth = 0;
        boolean inString = false;

        for (int i = startIdx; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == openBracket) {
                depth++;
            } else if (c == closeBracket) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private List<String> extractTopLevelObjects(String jsonArray) {
        List<String> objects = new ArrayList<>();
        int i = 0;
        while (i < jsonArray.length()) {
            char c = jsonArray.charAt(i);
            if (c == '{') {
                int end = findMatchingBracket(jsonArray, i);
                if (end > i) {
                    objects.add(jsonArray.substring(i, end + 1));
                    i = end + 1;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        return objects;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractToolCallWithRegex(String text) {
        Map<String, Object> call = new HashMap<>();

        Pattern namePat = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher nameMatcher = namePat.matcher(text);
        if (nameMatcher.find()) {
            call.put("name", nameMatcher.group(1));
        }

        int argStart = text.indexOf("\"arguments\"");
        if (argStart < 0) {
            argStart = text.indexOf("arguments");
        }
        if (argStart > 0) {
            int objStart = text.indexOf("{", argStart);
            if (objStart > 0) {
                int objEnd = findMatchingBracket(text, objStart);
                if (objEnd > objStart) {
                    String argsJson = text.substring(objStart, objEnd + 1);
                    try {
                        ObjectMapper lenientMapper = new ObjectMapper()
                                .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                                .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
                        Map<String, Object> args = lenientMapper.readValue(argsJson, Map.class);
                        call.put("arguments", args);
                    } catch (Exception e) {
                        Map<String, Object> fallbackArgs = extractArgumentsWithRegex(argsJson);
                        call.put("arguments", fallbackArgs);
                    }
                }
            }
        }

        return call.isEmpty() ? null : call;
    }

    private Map<String, Object> extractArgumentsWithRegex(String argsJson) {
        Map<String, Object> args = new HashMap<>();

        Pattern pathPat = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
        Matcher pathMatcher = pathPat.matcher(argsJson);
        if (pathMatcher.find()) {
            args.put("path", pathMatcher.group(1));
        }

        int contentKeyStart = argsJson.indexOf("\"content\"");
        if (contentKeyStart < 0) {
            contentKeyStart = argsJson.indexOf("content");
        }
        if (contentKeyStart > 0) {
            int colonIdx = argsJson.indexOf(":", contentKeyStart);
            int quoteStart = argsJson.indexOf("\"", colonIdx);
            if (quoteStart > colonIdx) {
                int endMarker = argsJson.lastIndexOf("\"");
                if (endMarker > quoteStart) {
                    String content = argsJson.substring(quoteStart + 1, endMarker);
                    content = content.replace("\\n", "\n").replace("\\t", "\t")
                                     .replace("\\\"", "\"").replace("\\\\", "\\");
                    args.put("content", content);
                }
            }
        }

        return args;
    }

    // ────────────────────────── tool call execution ──────────────────────────

    public String executeToolCall(String toolId, Map<String, Object> args, Node node, String schemaId) {
        return executeToolCall(toolId, args, node, schemaId, null, null);
    }

    public String executeToolCall(String toolId, Map<String, Object> args, Node node, String schemaId, String schemaTargetPath) {
        return executeToolCall(toolId, args, node, schemaId, schemaTargetPath, null);
    }

    public String executeToolCall(String toolId, Map<String, Object> args, Node node, String schemaId,
                                  String schemaTargetPath, String projectType) {
        ToolPermission permission = null;
        if (node.getData().getToolPermissions() != null) {
            for (ToolPermission tp : node.getData().getToolPermissions()) {
                if (tp.getToolId() != null && tp.getToolId().equals(toolId)) {
                    permission = tp;
                    break;
                }
            }
        }

        if (permission == null) {
            List<String> enabledTools = node.getData().getEnabledTools();
            if (enabledTools != null && enabledTools.contains(toolId)) {
                permission = new ToolPermission(toolId);
                permission.setEnabled(true);
            }
        }

        // Inject diff-review flag for file_write when stage config requires it
        if ("file_write".equals(toolId) && node.getData().getConfig() != null
                && Boolean.TRUE.equals(node.getData().getConfig().get("requireDiffReview"))) {
            args.put("_diffReview", true);
        }

        ToolResult result = toolExecutor.execute(toolId, args, permission, schemaId, node.getId(), schemaTargetPath, projectType);
        return result.isSuccess() ? result.getOutput() : "Error: " + result.getError();
    }

    // ────────────────────────── user approval ──────────────────────────

    public void sendUserApprovalRequest(String schemaId, String nodeId, int toolCallCount, int maxToolCalls) {
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "warning",
                    "Достигнут лимит инструментов (" + toolCallCount + "/" + maxToolCalls + "). Требуется подтверждение для продолжения.",
                    nodeId);
        }
    }

    // ────────────────────────── write output ──────────────────────────

    public String writeOutput(String outputType, String filePath, String fileFormat, String content) {
        if (content == null || content.isBlank()) {
            return "Нет данных для вывода";
        }
        if ("file".equals(outputType) && filePath != null && !filePath.isBlank()) {
            try {
                Path path = Path.of(filePath);
                Files.createDirectories(path.getParent());
                String dataToWrite = content;
                if ("json".equals(fileFormat)) {
                    dataToWrite = "{\n  \"result\": " + new ObjectMapper().writeValueAsString(content) + ",\n  \"timestamp\": " + System.currentTimeMillis() + "\n}";
                }
                Files.writeString(path, dataToWrite);
                return "Сохранено в файл: " + filePath;
            } catch (Exception e) {
                return "Ошибка записи файла: " + e.getMessage();
            }
        }
        return content;
    }

    // ────────────────────────── command sanitization ──────────────────────────

    public String sanitizeCommand(String command) {
        String lower = command.toLowerCase().trim();
        for (String blocked : BLOCKED_COMMAND_PATTERNS) {
            if (lower.contains(blocked.toLowerCase())) {
                throw new SecurityException("Command blocked: contains dangerous pattern '" + blocked + "'");
            }
        }
        if (lower.contains("$(rm ") || lower.contains("`rm ") || lower.contains("/dev/null >")) {
            throw new SecurityException("Command blocked: contains dangerous shell expansion");
        }
        return command;
    }

    // ────────────────────────── URL validation ──────────────────────────

    public void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                throw new SecurityException("Only http/https URLs allowed");
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new SecurityException("URL must have a valid host");
            }
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw new SecurityException("Access to internal network addresses is blocked");
            }
        } catch (java.net.UnknownHostException e) {
            throw new SecurityException("Cannot resolve host: " + e.getMessage());
        }
    }

    // ────────────────────────── path sandbox ──────────────────────────

    public boolean isPathAllowed(String filePath) {
        if (allowedWriteDirs == null || allowedWriteDirs.isEmpty()) return true;
        try {
            Path resolved = Path.of(filePath).toAbsolutePath().normalize();
            for (String dir : allowedWriteDirs) {
                Path allowedBase = Path.of(dir).toAbsolutePath().normalize();
                if (resolved.startsWith(allowedBase)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ────────────────────────── URL fetch ──────────────────────────

    public String fetchUrlContent(String url) {
        try {
            validateUrl(url);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String content = response.body();
                return content.length() > 50000 ? content.substring(0, 50000) : content;
            }
            return "Ошибка загрузки URL: HTTP " + response.statusCode();
        } catch (Exception e) {
            return "Ошибка загрузки URL: " + e.getMessage();
        }
    }

    // ────────────────────────── project context ──────────────────────────

    public String readProjectContext(String projectPath, Map<String, Object> config) {
        try {
            Path root = Path.of(projectPath);
            if (!Files.exists(root)) {
                return "Ошибка: путь не существует: " + projectPath;
            }

            int maxDepth = config != null && config.get("maxDepth") != null
                    ? ((Number) config.get("maxDepth")).intValue() : 4;
            int maxFiles = config != null && config.get("maxFiles") != null
                    ? ((Number) config.get("maxFiles")).intValue() : 50;

            Set<String> excludeDirs = Set.of(".git", "node_modules", ".idea", "target", "dist", "__pycache__", ".next", "build");
            Set<String> includeExtensions = config != null && config.get("includeExtensions") != null
                    ? new HashSet<>((List<String>) config.get("includeExtensions"))
                    : Set.of(".java", ".ts", ".tsx", ".vue", ".js", ".py", ".go", ".rs", ".yaml", ".yml", ".json", ".md", ".toml", ".xml", ".properties", ".sql", ".html", ".css");

            StringBuilder sb = new StringBuilder();
            sb.append("Project: ").append(root.getFileName()).append("\n");
            sb.append("Path: ").append(projectPath).append("\n\n");

            sb.append("=== FILE TREE ===\n");
            List<String> files = new ArrayList<>();
            Files.walk(root, maxDepth)
                    .filter(p -> {
                        for (int i = 0; i < p.getNameCount(); i++) {
                            if (excludeDirs.contains(p.getName(i).toString())) return false;
                        }
                        return true;
                    })
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        return dot >= 0 && includeExtensions.contains(name.substring(dot));
                    })
                    .limit(maxFiles)
                    .forEach(p -> files.add(root.relativize(p).toString()));

            for (String f : files) {
                sb.append("  ").append(f).append("\n");
            }
            sb.append("\n");

            sb.append("=== KEY FILES ===\n");
            List<String> priorityFiles = List.of("README.md", "CLAUDE.md", "package.json", "pom.xml", "Cargo.toml", "go.mod", "pyproject.toml");
            for (String pf : priorityFiles) {
                Path p = root.resolve(pf);
                if (Files.exists(p)) {
                    String content = Files.readString(p);
                    sb.append("\n--- ").append(pf).append(" ---\n");
                    sb.append(content.length() > 3000 ? content.substring(0, 3000) + "\n... (truncated)" : content);
                    sb.append("\n");
                }
            }

            boolean includeSources = config == null || !Boolean.FALSE.equals(config.get("includeSources"));
            if (includeSources) {
                sb.append("\n=== SOURCE FILES ===\n");
                int fileCount = 0;
                for (String f : files) {
                    if (fileCount >= 20) break;
                    Path p = root.resolve(f);
                    try {
                        String content = Files.readString(p);
                        if (content.length() > 2000) {
                            content = content.substring(0, 2000) + "\n... (truncated)";
                        }
                        sb.append("\n--- ").append(f).append(" ---\n");
                        sb.append(content);
                        sb.append("\n");
                        fileCount++;
                    } catch (Exception ignored) {}
                }
            }

            String result = sb.toString();
            return result.length() > 100000 ? result.substring(0, 100000) + "\n... (truncated)" : result;
        } catch (Exception e) {
            return "Ошибка чтения проекта: " + e.getMessage();
        }
    }

    // ────────────────────────── output node handler ──────────────────────────

    public String executeOutputNode(Node node, String schemaId, ExecutionMode mode) {
        if (mode == ExecutionMode.ANALYZE || mode == ExecutionMode.DRY_RUN) {
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "warning", "Блокировка: запись не выполняется в режиме " + mode, node.getId());
            }
            return "[SIMULATED] Output node - no file/memory operations";
        }

        Map<String, Object> config = node.getData() != null && node.getData().getConfig() != null
                ? node.getData().getConfig() : new HashMap<>();
        String outputMode = config.get("mode") instanceof String
                ? (String) config.get("mode") : "simple";

        var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
        String input = predResults.values().stream().findFirst().map(Object::toString).orElse("");

        if ("summary_report".equals(outputMode)) {
            return executeSummaryReportNode(node, schemaId, config, input);
        }

        String outputType = config.getOrDefault("outputType", "log") instanceof String
                ? (String) config.get("outputType") : "log";

        if ("memory".equals(outputType)) {
            String wing = config.getOrDefault("memoryWing", "axolotl") instanceof String
                    ? (String) config.get("memoryWing") : "axolotl";
            String room = config.getOrDefault("memoryRoom", "agent-results") instanceof String
                    ? (String) config.get("memoryRoom") : "agent-results";
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Сохранение в память");
                webSocketHandler.sendLog(schemaId, "info", "Сохранение в память: " + wing + "/" + room, node.getId());
            }
            boolean ok = memPalaceClient.addDrawer(wing, room, input, "schema:" + schemaId);
            String result = ok ? "Сохранено в память: " + wing + "/" + room : "Ошибка сохранения в память (MemPalace не подключен)";
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, ok ? "success" : "error", result, node.getId());
            }
            return result;
        }

        String filePath = config.getOrDefault("filePath", "") instanceof String
                ? (String) config.get("filePath") : "";
        String fileFormat = config.getOrDefault("fileFormat", "text") instanceof String
                ? (String) config.get("fileFormat") : "text";
        String result = writeOutput(outputType, filePath, fileFormat, input);
        if ("file".equals(outputType) && filePath != null && !filePath.isBlank()) {
            stateManager.getOutputFileRegistry().put(schemaId + ":" + node.getId(), filePath);
        }
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "Output: " + result, node.getId());
        }
        return result;
    }

    // ────────────────────────── summary report node ──────────────────────────

    public String executeSummaryReportNode(Node node, String schemaId, Map<String, Object> config, String input) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Building summary report");
            webSocketHandler.sendLog(schemaId, "info", "Building summary report", node.getId());
        }

        boolean includeReview = config.getOrDefault("includeReview", true) instanceof Boolean
                ? (Boolean) config.get("includeReview") : true;
        boolean includeFiles = config.getOrDefault("includeFiles", true) instanceof Boolean
                ? (Boolean) config.get("includeFiles") : true;
        boolean includeVerification = config.getOrDefault("includeVerification", true) instanceof Boolean
                ? (Boolean) config.get("includeVerification") : true;
        boolean includeMetrics = config.getOrDefault("includeMetrics", true) instanceof Boolean
                ? (Boolean) config.get("includeMetrics") : true;
        String reportPath = config.getOrDefault("reportPath", "pipeline-report.md") instanceof String
                ? (String) config.get("reportPath") : "pipeline-report.md";
        boolean generateReadme = config.getOrDefault("generateReadme", true) instanceof Boolean
                ? (Boolean) config.get("generateReadme") : true;
        boolean generateArchitecture = config.getOrDefault("generateArchitecture", false) instanceof Boolean
                ? (Boolean) config.get("generateArchitecture") : false;

        Map<String, String> nodeResultsMap = stateManager.getNodeResults().getOrDefault(schemaId, new ConcurrentHashMap<>());
        WorkflowSchema currentSchema = schemaRepository.findById(schemaId);

        StringBuilder report = new StringBuilder();
        report.append("# Pipeline Summary\n\n");

        if (includeReview) {
            report.append("## Plan (from Review)\n\n");
            if (currentSchema != null && currentSchema.getNodes() != null) {
                for (Node n : currentSchema.getNodes()) {
                    if ("review".equals(n.getType())) {
                        String reviewResult = nodeResultsMap.get(n.getId());
                        if (reviewResult != null && !reviewResult.isBlank()) {
                            try {
                                ObjectMapper mapper = new ObjectMapper();
                                JsonNode root = mapper.readTree(reviewResult);
                                int findingsCount = root.has("findings") && root.get("findings").isArray()
                                        ? root.get("findings").size() : 0;
                                String approvedBy = "auto";
                                if (root.has("status")) {
                                    String status = root.get("status").asText();
                                    if ("AWAITING_APPROVAL".equals(status)) {
                                        approvedBy = "pending";
                                    } else if ("PASS".equals(status)) {
                                        approvedBy = "human";
                                    }
                                }
                                int highCount = 0, medCount = 0;
                                if (root.has("findings") && root.get("findings").isArray()) {
                                    for (JsonNode f : root.get("findings")) {
                                        String sev = f.has("severity") ? f.get("severity").asText() : "";
                                        if ("critical".equals(sev) || "high".equals(sev)) highCount++;
                                        else if ("warning".equals(sev) || "medium".equals(sev)) medCount++;
                                    }
                                }
                                report.append("- Iterations: ").append(root.has("rewriteIterations") ? root.get("rewriteIterations").asText() : "1").append("\n");
                                report.append("- Approved by: ").append(approvedBy).append("\n");
                                report.append("- Findings: ").append(findingsCount)
                                        .append(" (").append(highCount).append(" HIGH, ").append(medCount).append(" MEDIUM)\n");
                                String planText = root.has("plan") ? root.get("plan").asText() : "";
                                if (planText.length() > 500) planText = planText.substring(0, 500) + "...";
                                report.append("- Plan: ").append(planText).append("\n");
                            } catch (Exception e) {
                                report.append("- Review result: ").append(reviewResult.length() > 200 ? reviewResult.substring(0, 200) + "..." : reviewResult).append("\n");
                            }
                        } else {
                            report.append("- No review data available\n");
                        }
                        break;
                    }
                }
            }
            report.append("\n");
        }

        if (includeFiles && currentSchema != null && currentSchema.getNodes() != null) {
            report.append("## Agent (from Think)\n\n");
            for (Node n : currentSchema.getNodes()) {
                if ("agent".equals(n.getType())) {
                    String agentResult = nodeResultsMap.get(n.getId());
                    String model = n.getData() != null ? n.getData().getModel() : "unknown";
                    report.append("- Model: ").append(model != null ? model : "unknown").append("\n");

                    String genKey = schemaId + ":" + n.getId();
                    Object genFiles = stateManager.getGeneratedFilesRegistry().get(genKey);
                    if (genFiles instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> files = (Map<String, Object>) genFiles;
                        report.append("- Generated files: ");
                        for (Map.Entry<String, Object> fe : files.entrySet()) {
                            report.append(fe.getKey()).append(" (").append(fe.getValue()).append(" lines), ");
                        }
                        if (report.charAt(report.length() - 2) == ',') {
                            report.setLength(report.length() - 2);
                        }
                        report.append("\n");
                    } else if (agentResult != null) {
                        int approxLines = agentResult.split("\n").length;
                        report.append("- Output lines: ").append(approxLines).append("\n");
                    }
                    break;
                }
            }
            report.append("\n");
        }

        if (includeVerification && currentSchema != null && currentSchema.getNodes() != null) {
            report.append("## Verification\n\n");
            for (Node n : currentSchema.getNodes()) {
                if ("verifier".equals(n.getType())) {
                    String verResult = nodeResultsMap.get(n.getId());
                    if (verResult != null && !verResult.isBlank()) {
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode root = mapper.readTree(verResult);
                            String verStatus = root.has("status") ? root.get("status").asText() : "UNKNOWN";
                            report.append("- Syntax check: ").append(verStatus.equals("PASS") ? "PASS" : "FAIL").append("\n");

                            if (root.has("premortemPredictions") && root.get("premortemPredictions").isArray()) {
                                int predCount = root.get("premortemPredictions").size();
                                report.append("- Premortem predictions: ").append(predCount);
                                if (root.has("checkResults") && root.get("checkResults").isArray()) {
                                    int confirmed = 0;
                                    for (JsonNode cr : root.get("checkResults")) {
                                        if (cr.has("passed") && !cr.get("passed").asBoolean()) confirmed++;
                                    }
                                    report.append(", Confirmed: ").append(confirmed)
                                            .append(", Fixed: ").append(confirmed);
                                }
                                report.append("\n");
                            }

                            int rewrites = root.has("rewriteRetries") ? root.get("rewriteRetries").asInt() : 0;
                            report.append("- Rewrite retries: ").append(rewrites).append("\n");

                            if (root.has("checkResults") && root.get("checkResults").isArray()) {
                                for (JsonNode cr : root.get("checkResults")) {
                                    String name = cr.has("name") ? cr.get("name").asText() : "check";
                                    boolean passed = !cr.has("passed") || cr.get("passed").asBoolean();
                                    report.append("  - ").append(name).append(": ").append(passed ? "PASS" : "FAIL").append("\n");
                                }
                            }
                        } catch (Exception e) {
                            report.append("- Verification result available\n");
                        }
                    } else {
                        report.append("- No verification data\n");
                    }
                    break;
                }
            }
            report.append("\n");
        }

        if (includeMetrics) {
            report.append("## Execution\n\n");
            int totalNodes = currentSchema != null && currentSchema.getNodes() != null
                    ? currentSchema.getNodes().size() : 0;
            int completedNodes = 0;
            if (currentSchema != null && currentSchema.getNodes() != null) {
                for (Node n : currentSchema.getNodes()) {
                    if (n.getStatus() == Node.NodeStatus.COMPLETED) {
                        completedNodes++;
                    }
                }
            }
            report.append("- Total nodes: ").append(totalNodes).append("\n");
            report.append("- Completed nodes: ").append(completedNodes).append("\n");
            if (input != null && !input.isBlank()) {
                if (input.contains("Total time") || input.contains("totalTime")) {
                    report.append("- Time info available in upstream\n");
                }
            }
            report.append("\n");
        }

        report.append("---\n*Generated by Axolotl Output Node (summary_report mode)*\n");

        String reportContent = report.toString();

        try {
            String targetDir = ".";
            if (currentSchema != null && currentSchema.getTargetPath() != null && !currentSchema.getTargetPath().isBlank()) {
                targetDir = currentSchema.getTargetPath();
            }
            Path reportFilePath = Path.of(targetDir, reportPath).normalize();
            Files.createDirectories(reportFilePath.getParent());
            Files.writeString(reportFilePath, reportContent);

            stateManager.getOutputFileRegistry().put(schemaId + ":" + node.getId(), reportFilePath.toString());

            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "success",
                        "Summary report written to: " + reportFilePath, node.getId());
            }

            // Generate README.md if configured
            if (generateReadme) {
                try {
                    Path readmePath = Path.of(targetDir, "README.md").normalize();
                    String readme = buildReadmeDoc(targetDir, currentSchema);
                    Files.writeString(readmePath, readme);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "success",
                                "README.md written to: " + readmePath, node.getId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to generate README.md: {}", e.getMessage());
                }
            }

            // Generate architecture.md if configured
            if (generateArchitecture) {
                try {
                    Path archPath = Path.of(targetDir, "architecture.md").normalize();
                    String arch = buildArchitectureDoc(targetDir, currentSchema);
                    Files.writeString(archPath, arch);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "success",
                                "architecture.md written to: " + archPath, node.getId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to generate architecture.md: {}", e.getMessage());
                }
            }

            return "Summary report written to: " + reportFilePath + "\n\n" + reportContent;
        } catch (Exception e) {
            log.error("Failed to write summary report: {}", e.getMessage(), e);
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error",
                        "Failed to write summary report: " + e.getMessage(), node.getId());
            }
            return "Summary report (write failed, returning inline):\n\n" + reportContent;
        }
    }

    // ────────────────────────── command node ──────────────────────────

    public String executeCommandNode(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Выполнение команды");
        }

        String command = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("command", "") : "";
        String workingDir = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("workingDir", "") : "";
        int timeout = node.getData() != null && node.getData().getConfig() != null
                ? (Integer) node.getData().getConfig().getOrDefault("timeout", 60) : 60;

        if (command == null || command.isBlank()) {
            return "Ошибка: команда не указана";
        }

        try {
            command = sanitizeCommand(command);
        } catch (SecurityException e) {
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error", "Command blocked: " + e.getMessage(), node.getId());
            }
            return "Blocked: " + e.getMessage();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            if (workingDir != null && !workingDir.isBlank()) {
                pb.directory(new java.io.File(workingDir));
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try {
                boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return "Таймаут после " + timeout + " сек";
                }
                int exitCode = process.exitValue();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString();
                node.getData().setResult(output);
                node.getData().setConfig(Map.of("exitCode", exitCode));
                String result = output.isEmpty() ? "(пусто)" : output;
                if (exitCode == 0) {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "success", "Команда выполнена (exit " + exitCode + ")", node.getId());
                    }
                } else {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "error", "Команда завершена с ошибкой (exit " + exitCode + ")", node.getId());
                    }
                }
                return result.trim();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return "Прервано: " + e.getMessage();
            }
        } catch (Exception e) {
            log.error("Ошибка выполнения команды: {}", e.getMessage(), e);
            return "Ошибка: " + e.getMessage();
        }
    }

    // ────────────────────────── file write node ──────────────────────────

    public String executeFileWriteNode(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Запись в файл");
        }

        String filePath = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("filePath", "") : "";
        String writeMode = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("writeMode", "overwrite") : "overwrite";

        if (filePath == null || filePath.isBlank()) {
            return "Ошибка: путь к файлу не указан";
        }

        String normalizedPath = Path.of(filePath).toAbsolutePath().normalize().toString();
        if (filePath.contains("..") || !isPathAllowed(normalizedPath)) {
            String msg = "Access denied: path outside allowed directories — " + filePath;
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error", msg, node.getId());
            }
            return msg;
        }

        var predResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
        String content = predResults.values().stream().findFirst().map(Object::toString).orElse("");

        try {
            java.io.File file = new java.io.File(filePath);
            if ("create-dir".equals(writeMode)) {
                java.io.File dir = file.getParentFile();
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
            }
            java.io.FileWriter writer = new java.io.FileWriter(file, "append".equals(writeMode));
            writer.write(content);
            writer.close();

            String result = "Записано в файл: " + filePath;
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "success", result, node.getId());
            }
            return result;
        } catch (Exception e) {
            log.error("Ошибка записи в файл: {}", e.getMessage(), e);
            return "Ошибка записи: " + e.getMessage();
        }
    }

    // ────────────────────────── subagent node ──────────────────────────

    public String executeSubagentNode(Node node, String schemaId, AtomicBoolean cancelFlag, ExecutionMode mode) {
        String targetSchemaId = node.getData() != null ? node.getData().getSubagentSchemaId() : null;
        if (targetSchemaId == null || targetSchemaId.isBlank()) {
            return "Ошибка: Subagent не указывает на схему";
        }

        WorkflowSchema targetSchema = schemaRepository.findById(targetSchemaId);
        if (targetSchema == null) {
            return "Ошибка: Схема не найдена: " + targetSchemaId;
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Запуск подсхемы: " + targetSchema.getName());
            webSocketHandler.sendLog(schemaId, "info", "→ Subagent: запуск " + targetSchema.getName(), node.getId());
        }

        StringBuilder nestedResult = new StringBuilder();
        nestedResult.append("=== Subagent: ").append(targetSchema.getName()).append(" ===\n");

        Map<String, Object> predecessorResults = collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());

        if (predecessorResults.isEmpty() && webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "  (без входных данных)", node.getId());
        }

        nestedResult.append("Входные данные: ").append(predecessorResults.values().stream().findFirst().map(Object::toString).orElse("(нет)")).append("\n");

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "  Результат: " + nestedResult, node.getId());
        }

        nestedResult.append("=== Subagent завершён ===");
        return nestedResult.toString();
    }

    // ────────────────────────── source node handling ──────────────────────────

    public String handleSourceNode(Node node, String schemaId) {
        String sourceType = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("sourceType", "text") : "text";

        if ("memory".equals(sourceType)) {
            String query = node.getData() != null && node.getData().getSourceData() != null
                    && !node.getData().getSourceData().isEmpty()
                    ? node.getData().getSourceData() : node.getName();
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Поиск в памяти");
                webSocketHandler.sendLog(schemaId, "info", "Поиск в памяти: " + query, node.getId());
            }
            if (memPalaceClient.isEnabled()) {
                var memResults = memPalaceClient.search(query, null, null, 5);
                if (memResults.isEmpty()) {
                    return "Ничего не найдено по запросу: " + query;
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (var r : memResults) sb.append("- ").append(r.get("content")).append("\n");
                    return sb.toString().trim();
                }
            } else {
                return "Память недоступна (MemPalace не подключен)";
            }
        } else if ("url".equals(sourceType)) {
            String url = node.getData() != null && node.getData().getConfig() != null
                    ? (String) node.getData().getConfig().getOrDefault("url", "") : "";
            if (url.isEmpty()) return "URL не указан";
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Загрузка URL");
                webSocketHandler.sendLog(schemaId, "info", "Загрузка URL: " + url, node.getId());
            }
            return fetchUrlContent(url);
        } else if ("project".equals(sourceType)) {
            String projectPath = node.getData() != null && node.getData().getConfig() != null
                    ? (String) node.getData().getConfig().getOrDefault("projectPath", "") : "";
            if (projectPath.isEmpty()) return "Путь к проекту не указан";
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 30, "Чтение структуры проекта");
                webSocketHandler.sendLog(schemaId, "info", "Чтение проекта: " + projectPath, node.getId());
            }
            return readProjectContext(projectPath,
                    node.getData() != null ? node.getData().getConfig() : null);
        } else if ("file".equals(sourceType)) {
            // Use embedded content first (uploaded via frontend FileReader)
            String sourceData = node.getData() != null ? node.getData().getSourceData() : null;
            if (sourceData == null || sourceData.isEmpty()) {
                // Fallback to config.sourceData for backward compatibility
                sourceData = node.getData() != null && node.getData().getConfig() != null
                        ? (String) node.getData().getConfig().get("sourceData") : null;
            }
            if (sourceData != null && !sourceData.isEmpty()) {
                return sourceData;
            }
            String filePath = node.getData() != null && node.getData().getConfig() != null
                    ? (String) node.getData().getConfig().getOrDefault("filePath", "") : "";
            if (filePath == null || filePath.isEmpty()) {
                return "Файл не указан";
            }
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Чтение файла");
                webSocketHandler.sendLog(schemaId, "info", "Чтение файла: " + filePath, node.getId());
            }
            try {
                Path resolved = Path.of(filePath);
                if (!resolved.isAbsolute()) {
                    WorkflowSchema currentSchema = schemaRepository.findById(schemaId);
                    if (currentSchema != null && currentSchema.getTargetPath() != null
                            && !currentSchema.getTargetPath().isBlank()) {
                        resolved = Path.of(currentSchema.getTargetPath(), filePath).normalize();
                    }
                }
                long maxSize = 1024 * 1024; // 1MB
                if (Files.size(resolved) > maxSize) {
                    return "Файл слишком большой: " + resolved.getFileName();
                }
                return Files.readString(resolved, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.nio.file.NoSuchFileException e) {
                return "Файл не найден: " + filePath;
            } catch (Exception e) {
                log.error("Ошибка чтения файла {}: {}", filePath, e.getMessage());
                return "Ошибка чтения файла: " + e.getMessage();
            }
        } else {
            // text mode (default)
            if (node.getData() != null && node.getData().getSourceData() != null
                    && !node.getData().getSourceData().isEmpty()) {
                return node.getData().getSourceData();
            } else {
                return "Данные из источника: " + node.getName();
            }
        }
    }

    // ────────────────────────── Test accessors (package-private) ──────────────────────────

    String sanitizeCommandPublic(String command) { return sanitizeCommand(command); }
    void validateUrlPublic(String url) { validateUrl(url); }
    boolean isPathAllowedPublic(String path) { return isPathAllowed(path); }
    public boolean evaluateConditionPublic(String expr, java.util.Map<String, Object> ctx) { return evaluateCondition(expr, ctx); }
    public String interpolateVariablesPublic(String text, WorkflowSchema schema, java.util.Map<String, Object> preds) { return interpolateVariables(text, schema, preds); }
    public String buildContextBlockPublic(java.util.Map<String, Object> preds) { return buildContextBlock(preds); }
    public String writeOutputPublic(String outputType, String filePath, String fileFormat, String content) { return writeOutput(outputType, filePath, fileFormat, content); }
    public boolean sleepWithCancelPublic(long millis, java.util.concurrent.atomic.AtomicBoolean cancelFlag) { return sleepWithCancel(millis, cancelFlag); }
    public String executeOutputNodePublic(Node node, String schemaId, ExecutionMode mode) { return executeOutputNode(node, schemaId, mode); }

    // ────────────────────────── documentation generation ──────────────────────────

    String buildReadmeDoc(String targetDir, WorkflowSchema schema) {
        Path dir = Path.of(targetDir);
        StringBuilder md = new StringBuilder();
        String projectName = (schema != null && schema.getName() != null && !schema.getName().isBlank())
                ? schema.getName() : "Project";

        md.append("# ").append(projectName).append("\n\n");
        String desc = schema != null ? schema.getDescription() : null;
        if (desc != null && !desc.isBlank()) {
            md.append(desc).append("\n\n");
        }

        // File tree
        md.append("## Project Structure\n\n");
        md.append("```\n");
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream
                    .filter(p -> {
                        String rel = dir.relativize(p).toString();
                        return !rel.isEmpty()
                            && !rel.startsWith(".git") && !rel.startsWith("node_modules")
                            && !rel.equals("README.md") && !rel.equals("architecture.md")
                            && !rel.equals("pipeline-report.md");
                    })
                    .sorted()
                    .forEach(p -> {
                        String rel = dir.relativize(p).toString();
                        if (Files.isDirectory(p)) {
                            md.append(rel).append("/\n");
                        } else {
                            md.append(rel).append("\n");
                        }
                    });
            } catch (Exception e) {
                md.append("(file tree unavailable)\n");
            }
        }
        md.append("```\n\n");

        // Build/run instructions from common config files
        readBuildInstructions(dir, md);

        md.append("---\n*Generated by Axolotl*\n");
        return md.toString();
    }

    String buildArchitectureDoc(String targetDir, WorkflowSchema schema) {
        Path dir = Path.of(targetDir);
        StringBuilder md = new StringBuilder();
        String projectName = (schema != null && schema.getName() != null && !schema.getName().isBlank())
                ? schema.getName() : "Project";

        md.append("# ").append(projectName).append(" Architecture\n\n");

        // Entry points
        md.append("## Entry Points\n\n");
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                List<Path> entryCandidates = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.equals("main.java") || name.equals("main.kt")
                            || name.equals("main.swift") || name.equals("app.vue")
                            || name.equals("index.html") || name.equals("main.ts")
                            || name.equals("main.py") || name.startsWith("entry");
                    })
                    .map(p -> dir.relativize(p))
                    .sorted()
                    .toList();
                if (entryCandidates.isEmpty()) {
                    md.append("No entry points detected.\n");
                } else {
                    for (Path ep : entryCandidates) {
                        md.append("- `").append(ep).append("`\n");
                    }
                }
            } catch (Exception e) {
                md.append("(scan unavailable)\n");
            }
        }
        md.append("\n");

        // File tree by directory grouping
        md.append("## Module Overview\n\n");
        md.append("```\n");
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream
                    .filter(p -> {
                        String rel = dir.relativize(p).toString();
                        return !rel.isEmpty()
                            && !rel.startsWith(".git") && !rel.startsWith("node_modules")
                            && !rel.equals("README.md") && !rel.equals("architecture.md")
                            && !rel.equals("pipeline-report.md");
                    })
                    .sorted()
                    .forEach(p -> {
                        String rel = dir.relativize(p).toString();
                        if (Files.isDirectory(p)) {
                            md.append(rel).append("/\n");
                        } else {
                            md.append(rel).append("\n");
                        }
                    });
            } catch (Exception e) {
                md.append("(file tree unavailable)\n");
            }
        }
        md.append("```\n\n");

        md.append("---\n*Generated by Axolotl*\n");
        return md.toString();
    }

    private void readBuildInstructions(Path dir, StringBuilder md) {
        // package.json (Node.js projects)
        Path pkg = dir.resolve("package.json");
        if (Files.exists(pkg)) {
            try {
                String content = Files.readString(pkg);
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(content);
                JsonNode scripts = root.get("scripts");
                if (scripts != null) {
                    md.append("## Setup & Run\n\n");
                    md.append("```bash\n");
                    md.append("# Install dependencies\nnpm install\n\n");
                    md.append("# Run\n");
                    if (scripts.has("dev")) md.append("npm run dev\n");
                    else if (scripts.has("start")) md.append("npm start\n");
                    else if (scripts.has("build")) md.append("npm run build\n");
                    md.append("```\n\n");
                }
            } catch (Exception e) {
                // skip silently — corrupt package.json is not our problem
            }
            return;
        }

        // build.gradle.kts or build.gradle (Gradle projects)
        Path gradle = dir.resolve("build.gradle.kts");
        if (!Files.exists(gradle)) gradle = dir.resolve("build.gradle");
        if (Files.exists(gradle)) {
            md.append("## Setup & Run\n\n");
            md.append("```bash\n");
            md.append("chmod +x gradlew\n");
            md.append("./gradlew build\n");
            md.append("```\n\n");
            return;
        }

        // Package.swift (Swift projects)
        if (Files.exists(dir.resolve("Package.swift"))) {
            md.append("## Setup & Run\n\n");
            md.append("```bash\n");
            md.append("swift build\n");
            md.append("swift run\n");
            md.append("```\n\n");
            return;
        }

        // Makefile
        if (Files.exists(dir.resolve("Makefile"))) {
            md.append("## Setup & Run\n\n");
            md.append("```bash\nmake\n```\n\n");
        }
    }
}
