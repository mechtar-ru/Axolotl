package com.agent.orchestrator.service;

import com.agent.orchestrator.llm.BtcaClient;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.client.PlaywrightClient;
import com.agent.orchestrator.model.Skill;
import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolCategory;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

@Service
public class ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolExecutorHandler> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private LlmService llmService;
    private MemPalaceClient memPalaceClient;
    private BtcaClient btcaClient;
    private SkillService skillService;
    private String workspacePath;

    private ExecutionWebSocketHandler webSocketHandler;

    // Pending user questions (schemaId -> pending question data)
    private final Map<String, PendingQuestion> pendingQuestions = new ConcurrentHashMap<>();

    // Data class for pending user question
    public static class PendingQuestion {
        private final String question;
        private final List<String> options;
        private final String schemaId;
        private final String nodeId;
        private volatile String answer;

        public PendingQuestion(String question, List<String> options, String schemaId, String nodeId) {
            this.question = question;
            this.options = options;
            this.schemaId = schemaId;
            this.nodeId = nodeId;
        }

        public String getQuestion() { return question; }
        public List<String> getOptions() { return options; }
        public String getSchemaId() { return schemaId; }
        public String getNodeId() { return nodeId; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public boolean hasAnswer() { return answer != null && !answer.isBlank(); }
    }

    private static final Set<String> DEFAULT_BLOCKED_COMMANDS = Set.of(
        "rm -rf", "rm -r /", "del /", "format",
        "mkfs", "dd if=", "> /dev/sd", ":(){",
        "curl | sh", "wget | sh", "bash -i", "python -m SimpleHTTPServer",
        "nc -e", "/bin/sh -i", "nohup ", "pkill -9",
        "chmod 777 /", "chown -R", "fdisk", "parted"
    );

    private static final java.util.concurrent.ConcurrentHashMap<String, String> webhooks = new java.util.concurrent.ConcurrentHashMap<>();

    public static void registerWebhook(String event, String url) {
        webhooks.put(event, url);
    }

    public static void sendWebhook(String event, Map<String, Object> payload) {
        String url = webhooks.get(event);
        if (url == null || url.isBlank()) return;
        
        try {
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();
            client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.out.println("Webhook error: " + e.getMessage());
        }
    }

    public void sendHumanPauseWebhook(String schemaId, String nodeId, String prompt) {
        sendWebhook("human_pause_request", Map.of(
            "event", "human_pause_request",
            "schemaId", schemaId,
            "nodeId", nodeId,
            "prompt", prompt,
            "timestamp", java.time.Instant.now().toString()
        ));
    }

    public void sendHumanApprovalWebhook(String schemaId, String nodeId, String prompt) {
        sendWebhook("human_approval_needed", Map.of(
            "event", "human_approval_needed",
            "schemaId", schemaId,
            "nodeId", nodeId,
            "prompt", prompt,
            "timestamp", java.time.Instant.now().toString()
        ));
    }

    public void sendHumanQuestionWebhook(String schemaId, String nodeId, String question) {
        sendWebhook("human_question", Map.of(
            "event", "human_question",
            "schemaId", schemaId,
            "nodeId", nodeId,
            "question", question,
            "timestamp", java.time.Instant.now().toString()
        ));
    }

    public ToolExecutor() {
        registerDefaultTools();
    }

    @PostConstruct
    public void initWorkspacePath() {
        String path = System.getProperty("AXOLOTL_WORKSPACE_PATH");
        if (path != null && !path.isEmpty()) {
            this.workspacePath = path;
            log.debug("ToolExecutor: workspacePath set to {}", path);
        }
    }

    private void registerDefaultTools() {
        registerTool(new Tool("file_read", "Read File", "Read contents of a file", """
            {"type":"object","properties":{"path":{"type":"string","description":"Absolute path to file"}},"required":["path"]}
            """, ToolCategory.FILE_SYSTEM));

        registerTool(new Tool("file_write", "Write File", "Write content to a file", """
            {"type":"object","properties":{"path":{"type":"string","description":"Absolute path to file"},"content":{"type":"string","description":"Content to write"}},"required":["path","content"]}
            """, ToolCategory.FILE_SYSTEM));

        registerTool(new Tool("directory_read", "List Directory", "List files in a directory", """
            {"type":"object","properties":{"path":{"type":"string","description":"Absolute path to directory"}},"required":["path"]}
            """, ToolCategory.FILE_SYSTEM));

        registerTool(new Tool("bash", "Bash", "Execute a bash command", """
            {"type":"object","properties":{"command":{"type":"string","description":"Command to execute"},"cwd":{"type":"string","description":"Working directory"},"timeout":{"type":"number","description":"Timeout in seconds"}},"required":["command"]}
            """, ToolCategory.EXECUTION));

        registerTool(new Tool("memory_read", "Memory Read", "Query from memory store", """
            {"type":"object","properties":{"query":{"type":"string","description":"Search query"},"limit":{"type":"number","description":"Max results"}},"required":["query"]}
            """, ToolCategory.MEMORY));

        registerTool(new Tool("memory_write", "Memory Write", "Write to memory store", """
            {"type":"object","properties":{"content":{"type":"string","description":"Content to store"},"metadata":{"type":"object","description":"Optional metadata"}},"required":["content"]}
            """, ToolCategory.MEMORY));

        registerTool(new Tool("web_search", "Web Search", "Search the web", """
            {"type":"object","properties":{"query":{"type":"string","description":"Search query"},"numResults":{"type":"number","description":"Number of results"}},"required":["query"]}
            """, ToolCategory.HTTP));

        registerTool(new Tool("web_fetch", "Web Fetch", "Fetch a URL", """
            {"type":"object","properties":{"url":{"type":"string","description":"URL to fetch"}},"required":["url"]}
            """, ToolCategory.HTTP));

        registerTool(new Tool("rlm_predict", "RLM Predict", "Call sub-LM with DSPy signature for structured extraction", """
            {"type":"object","properties":{"signature":{"type":"string","description":"DSPy signature (e.g. 'input -> output)"},"task":{"type":"string","description":"Task instruction"},"data":{"type":"string","description":"Input data"}},"required":["signature","task","data"]}
            """, ToolCategory.EXECUTION));

        registerTool(new Tool("grep", "Grep", "Search files for pattern matches", """
            {"type":"object","properties":{"pattern":{"type":"string","description":"Regex pattern to search for"},"path":{"type":"string","description":"Directory to search in"},"include":{"type":"string","description":"File glob filter (e.g. *.java)"},"maxResults":{"type":"number","description":"Max results (default 100)"}},"required":["pattern"]}
            """, ToolCategory.FILE_SYSTEM));

        registerTool(new Tool("file_delete", "Delete File", "Delete a file", """
            {"type":"object","properties":{"path":{"type":"string","description":"Path to file to delete"}},"required":["path"]}
            """, ToolCategory.FILE_SYSTEM));

registerTool(new Tool("file_move", "Move File", "Move or rename a file", """
            {"type":"object","properties":{"source":{"type":"string","description":"Source path"},"destination":{"type":"string","description":"Destination path"}},"required":["source","destination"]}
            """, ToolCategory.FILE_SYSTEM));

        registerTool(new Tool("user_ask", "Ask User", "Ask a question and wait for human response", """
            {"type":"object","properties":{"question":{"type":"string","description":"Question to ask the user"},"options":{"type":"array","items":{"type":"string"},"description":"Optional multiple choice options"}},"required":["question"]}
            """, ToolCategory.HUMAN));

        registerTool(new Tool("browser_automate", "Browser Automation", "Automate browser actions (navigate, click, type, screenshot)", """
            {"type":"object","properties":{"action":{"type":"string","enum":["navigate","click","type","screenshot","evaluate"]},"url":{"type":"string","description":"URL for navigate action"},"selector":{"type":"string","description":"CSS selector for click/type"},"text":{"type":"string","description":"Text to type"},"script":{"type":"string","description":"JavaScript to evaluate"}},"required":["action"]}
            """, ToolCategory.HTTP));
    }

    public void registerTool(Tool tool) {
        tools.put(tool.getId(), tool);
    }

    public Tool getTool(String toolId) {
        return tools.get(toolId);
    }

    public Map<String, Tool> getAllTools() {
        return Collections.unmodifiableMap(tools);
    }

    public List<Tool> getToolsByCategory(ToolCategory category) {
        return tools.values().stream()
            .filter(t -> t.getCategory() == category)
            .collect(Collectors.toList());
    }

    public ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission) {
        return execute(toolId, params, permission, null, null);
    }

    public ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission, String schemaId, String nodeId) {
        long startTime = System.currentTimeMillis();
        Tool tool = tools.get(toolId);
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + toolId);
        }
        if (permission != null && !permission.isEnabled()) {
            return ToolResult.error("Tool disabled by permissions: " + toolId);
        }

        if ("user_ask".equals(toolId)) {
            return handleUserAsk(params, schemaId, nodeId);
        }

        if ("browser_automate".equals(toolId)) {
            return handleBrowserAutomate(params, schemaId, nodeId);
        }

        if ("directory_read".equals(toolId)) {
            return handleDirectoryRead(params, permission);
        }

        if ("file_read".equals(toolId)) {
            return handleFileRead(params, permission);
        }

        if ("grep".equals(toolId)) {
            return handleGrep(params, permission);
        }

        ToolExecutorHandler handler = handlers.get(toolId);
        if (handler == null) {
            return ToolResult.error("No handler for tool: " + toolId);
        }

        try {
            ToolResult result = handler.execute(params, permission);
            long durationMs = System.currentTimeMillis() - startTime;

            if (schemaId != null && nodeId != null && webSocketHandler != null) {
                String argsJson = params != null ? params.toString() : "";
                webSocketHandler.sendToolCall(schemaId, nodeId, toolId, argsJson, durationMs, result.isSuccess(), result.getOutput());
            }

            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            if (schemaId != null && nodeId != null && webSocketHandler != null) {
                webSocketHandler.sendToolCall(schemaId, nodeId, toolId, params != null ? params.toString() : "",
                        durationMs, false, e.getMessage());
            }
            return ToolResult.error(e.getMessage());
        }
    }

    private ToolResult handleUserAsk(Map<String, Object> params, String schemaId, String nodeId) {
        String question = (String) params.get("question");
        if (question == null || question.isBlank()) {
            return ToolResult.error("Missing question parameter");
        }

        List<String> options = null;
        Object optObj = params.get("options");
        if (optObj instanceof List) {
            options = (List<String>) optObj;
        }

        if (schemaId == null) {
            return ToolResult.error("No schemaId for user_ask");
        }

PendingQuestion pq = new PendingQuestion(question, options, schemaId, nodeId);
        pendingQuestions.put(schemaId, pq);
        
        sendHumanQuestionWebhook(schemaId, nodeId, question);
        sendHumanPauseWebhook(schemaId, nodeId, question);

        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "info", "❓ " + question, nodeId);
        }

        return ToolResult.waiting("WAITING", "Question sent to user. Use /api/schemas/" + schemaId + "/answer to respond.");
    }

    private PlaywrightClient playwrightClient;

    private ToolResult handleBrowserAutomate(Map<String, Object> params, String schemaId, String nodeId) {
        String action = (String) params.get("action");
        if (action == null || action.isBlank()) {
            return ToolResult.error("Missing action parameter");
        }

        if (playwrightClient != null && playwrightClient.isAvailable()) {
            try {
                Map<String, Object> args = new LinkedHashMap<>(params);
                String result = playwrightClient.executeTool(action, args);
                return ToolResult.ok("[browser] " + result);
            } catch (Exception e) {
                log.error("Playwright error: {}", e.getMessage());
                return ToolResult.error("Browser error: " + e.getMessage());
            }
        }

        switch (action) {
            case "navigate":
                String url = (String) params.get("url");
                if (url == null || url.isBlank()) {
                    return ToolResult.error("Missing url parameter for navigate action");
                }
                return ToolResult.ok("[browser] Navigate to: " + url + " (use web_fetch for HTTP)");
            case "screenshot":
                return ToolResult.ok("[browser] Screenshot captured (requires Playwright)");
            case "click":
            case "type":
            case "evaluate":
                return ToolResult.ok("[browser] " + action + " (requires Playwright)");
            default:
                return ToolResult.error("Unknown action: " + action);
        }
    }

    public void setWebSocketHandler(ExecutionWebSocketHandler handler) {
        this.webSocketHandler = handler;
    }

    public void setLlmService(LlmService llmService) {
        this.llmService = llmService;
    }

    public void setMemPalaceClient(MemPalaceClient memPalaceClient) {
        this.memPalaceClient = memPalaceClient;
    }

    public void setSkillService(SkillService skillService) {
        this.skillService = skillService;
    }

    public void setPlaywrightClient(PlaywrightClient client) {
        this.playwrightClient = client;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public String getWorkspacePath() {
        return this.workspacePath;
    }

    private Path resolvePath(String path) {
        if (path == null) return null;
        Path p = Path.of(path);
        if (p.isAbsolute()) return p;
        if (workspacePath != null) return Path.of(workspacePath).resolve(path);
        return p;
    }

    private ToolResult handleFileRead(Map<String, Object> params, ToolPermission permission) {
        String path = (String) params.get("path");
        if (path == null) return ToolResult.error("Missing path parameter");

        try {
            String content = Files.readString(resolvePath(path));
            return ToolResult.ok(content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    private ToolResult handleFileWrite(Map<String, Object> params, ToolPermission permission) {
        String path = (String) params.get("path");
        String content = (String) params.get("content");
        if (path == null || content == null) return ToolResult.error("Missing path or content");

        try {
            Files.writeString(resolvePath(path), content);
            return ToolResult.ok("File written: " + path);
        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    private ToolResult handleDirectoryRead(Map<String, Object> params, ToolPermission permission) {
        String path = (String) params.get("path");
        if (path == null) {
            path = workspacePath != null ? workspacePath : ".";
        }

        try (Stream<Path> stream = Files.list(resolvePath(path))) {
            List<String> files = stream.map(p -> p.toString()).sorted().collect(Collectors.toList());
            return ToolResult.ok(String.join("\n", files));
        } catch (IOException e) {
            return ToolResult.error("Failed to list directory: " + e.getMessage());
        }
    }

    private ToolResult handleBash(Map<String, Object> params, ToolPermission permission) {
        String command = (String) params.get("command");
        String cwd = (String) params.get("cwd");
        Integer timeout = params.get("timeout") != null ? (Integer) params.get("timeout") : 30;

        if (command == null) return ToolResult.error("Missing command parameter");

        if (permission != null && !permission.allowsCommand(command)) {
            return ToolResult.error("Command blocked by permissions: " + command);
        }

        for (String blocked : DEFAULT_BLOCKED_COMMANDS) {
            if (command.toLowerCase().contains(blocked)) {
                return ToolResult.error("Dangerous command blocked: " + blocked);
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            if (cwd != null) pb.directory(new File(cwd));
            else if (workspacePath != null) pb.directory(new File(workspacePath));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Command timed out after " + timeout + "s");
            }

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return ToolResult.error("Exit code " + exitCode + ": " + output);
            }
            return ToolResult.ok(output);
        } catch (Exception e) {
            return ToolResult.error("Execution failed: " + e.getMessage());
        }
    }

    private ToolResult handleMemoryRead(Map<String, Object> params, ToolPermission permission) {
        String query = (String) params.get("query");
        Integer limit = params.get("limit") != null ? ((Number) params.get("limit")).intValue() : 5;

        if (memPalaceClient == null || !memPalaceClient.isEnabled()) {
            return ToolResult.ok("[MemPalace not available - client not configured or disabled]");
        }

        try {
            var results = memPalaceClient.search(query, null, null, limit);
            if (results.isEmpty()) {
                return ToolResult.ok("No memories found for query: " + query);
            }
            StringBuilder sb = new StringBuilder();
            for (var r : results) {
                sb.append("[score=").append(r.get("score")).append("] ");
                sb.append(r.get("content"));
                sb.append(" (wing=").append(r.get("wing")).append(", room=").append(r.get("room")).append(")\n");
            }
            return ToolResult.ok(sb.toString());
        } catch (Exception e) {
            return ToolResult.ok("[MemPalace error: " + e.getMessage() + "]");
        }
    }

    private ToolResult handleMemoryWrite(Map<String, Object> params, ToolPermission permission) {
        String content = (String) params.get("content");
        if (content == null) return ToolResult.error("Missing content parameter");

        if (memPalaceClient == null || !memPalaceClient.isEnabled()) {
            return ToolResult.ok("[MemPalace not available - client not configured or disabled]");
        }

        try {
            Map<String, Object> metadata = params.get("metadata") instanceof Map
                    ? (Map<String, Object>) params.get("metadata") : Map.of();
            String sourceFile = metadata != null ? (String) metadata.get("source_file") : null;
            boolean success = memPalaceClient.addDrawer("axolotl", "skills", content, sourceFile);
            return success ? ToolResult.ok("Memory written to MemPalace (wing=axolotl, room=skills)")
                           : ToolResult.error("Failed to write to MemPalace");
        } catch (Exception e) {
            return ToolResult.ok("[MemPalace write error: " + e.getMessage() + "]");
        }
    }

    private ToolResult handleWebSearch(Map<String, Object> params, ToolPermission permission) {
        String query = (String) params.get("query");
        Integer numResults = params.get("numResults") != null ? (Integer) params.get("numResults") : 5;

        return ToolResult.ok("[Web search placeholder - implement with actual search API]");
    }

    private ToolResult handleWebFetch(Map<String, Object> params, ToolPermission permission) {
        String url = (String) params.get("url");
        if (url == null) return ToolResult.error("Missing url parameter");

        return ToolResult.ok("[Web fetch placeholder - implement with HTTP client]");
    }

    private ToolResult handleRlmPredict(Map<String, Object> params, ToolPermission permission) {
        String signature = (String) params.get("signature");
        String task = (String) params.get("task");
        String data = (String) params.get("data");

        if (signature == null || task == null || data == null) {
            return ToolResult.error("Missing required parameters: signature, task, data");
        }

        if (llmService == null) {
            return ToolResult.error("LLM service not configured");
        }

        try {
            String fullPrompt = String.format("""
                Using signature: %s

                Task: %s

                Input data:
                %s

                Provide structured output following the signature.
                """, signature, task, data);

            String result = llmService.chat("ollama", null, fullPrompt, null);
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.error("RLM predict failed: " + e.getMessage());
        }
    }

    private ToolResult handleGrep(Map<String, Object> params, ToolPermission permission) {
        String pattern = (String) params.get("pattern");
        String path = (String) params.get("path");
        String include = (String) params.get("include");
        Integer maxResults = params.get("maxResults") != null ? ((Number) params.get("maxResults")).intValue() : 100;

        if (pattern == null) return ToolResult.error("Missing pattern parameter");

        Path searchDir = path != null ? resolvePath(path) : (workspacePath != null ? Path.of(workspacePath) : Path.of("."));

        try {
            Pattern regex = Pattern.compile(pattern);
            PathMatcher matcher = include != null ? FileSystems.getDefault().getPathMatcher("glob:" + include) : null;

            List<String> results = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(searchDir)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> matcher == null || matcher.matches(p.getFileName()))
                    .forEach(file -> {
                        if (results.size() >= maxResults) return;
                        try {
                            List<String> lines = Files.readAllLines(file);
                            for (int i = 0; i < lines.size() && results.size() < maxResults; i++) {
                                if (regex.matcher(lines.get(i)).find()) {
                                    results.add(file + ":" + (i + 1) + ": " + lines.get(i));
                                }
                            }
                        } catch (IOException e) {
                            results.add(file + ": [read error: " + e.getMessage() + "]");
                        }
                    });
            }

            return ToolResult.ok(results.isEmpty() ? "No matches found" : String.join("\n", results));
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Invalid regex pattern: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.error("Failed to search: " + e.getMessage());
        }
    }

    private ToolResult handleFileDelete(Map<String, Object> params, ToolPermission permission) {
        String path = (String) params.get("path");
        if (path == null) return ToolResult.error("Missing path parameter");

        try {
            Path resolved = resolvePath(path);
            boolean deleted = Files.deleteIfExists(resolved);
            return deleted ? ToolResult.ok("File deleted: " + path) : ToolResult.error("File not found: " + path);
        } catch (IOException e) {
            return ToolResult.error("Failed to delete file: " + e.getMessage());
        }
    }

    private ToolResult handleFileMove(Map<String, Object> params, ToolPermission permission) {
        String source = (String) params.get("source");
        String destination = (String) params.get("destination");
        if (source == null || destination == null) return ToolResult.error("Missing source or destination");

        try {
            Path src = resolvePath(source);
            Path dst = resolvePath(destination);
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return ToolResult.ok("Moved: " + source + " -> " + destination);
        } catch (IOException e) {
            return ToolResult.error("Failed to move file: " + e.getMessage());
        }
    }

    private static final Set<String> GIT_WHITELIST = Set.of(
        "status", "diff", "add", "commit", "log", "branch", "checkout", "merge", "push"
    );

    private ToolResult handleGit(Map<String, Object> params, ToolPermission permission) {
        String operation = (String) params.get("operation");
        String args = (String) params.get("args");

        if (operation == null) return ToolResult.error("Missing operation parameter");
        if (!GIT_WHITELIST.contains(operation)) {
            return ToolResult.error("Git operation '" + operation + "' not allowed. Allowed: " + GIT_WHITELIST);
        }

        try {
            String workDir = workspacePath != null ? workspacePath : ".";
            ProcessBuilder pb = new ProcessBuilder("git", "-C", workDir, operation);
            if (args != null && !args.isBlank()) {
                pb.command().addAll(java.util.Arrays.asList(args.split("\\s+")));
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Git command timed out after 30s");
            }

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return ToolResult.error("Git " + operation + " failed (exit " + exitCode + "): " + output);
            }
            return ToolResult.ok(output);
        } catch (Exception e) {
            return ToolResult.error("Git execution failed: " + e.getMessage());
        }
    }

    private ToolResult handleVerifyBuild(Map<String, Object> params, ToolPermission permission) {
        String path = params.get("path") != null ? (String) params.get("path") :
                (workspacePath != null ? workspacePath + "/frontend" : "frontend");
        String commands = params.get("commands") != null ? (String) params.get("commands") : "build,type-check";

        String[] cmds = commands.split(",");
        StringBuilder result = new StringBuilder();

        try {
            for (String cmd : cmds) {
                cmd = cmd.trim();
                result.append(cmd.toUpperCase()).append(": ");

                ProcessBuilder pb = new ProcessBuilder("npm", "run", cmd);
                pb.directory(new File(path));
                pb.redirectErrorStream(true);
                Process process = pb.start();

                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    result.append("FAIL (timeout)\n");
                    continue;
                }

                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    result.append("FAIL\n").append(output).append("\n");
                } else {
                    result.append("PASS\n");
                }
            }
            return ToolResult.ok(result.toString());
        } catch (Exception e) {
            return ToolResult.error("Build verification failed: " + e.getMessage());
        }
    }

    private ToolResult handleBtcaAsk(Map<String, Object> params, ToolPermission permission) {
        String tech = (String) params.get("tech");
        String question = (String) params.get("question");
        if (tech == null || question == null) return ToolResult.error("Missing tech or question parameter");

        if (btcaClient == null || !btcaClient.isEnabled()) {
            return ToolResult.ok("[btca not configured. Set axolotl.btca.enabled=true in application.yml]");
        }

        try {
            String answer = btcaClient.ask(tech, question);
            return ToolResult.ok(answer);
        } catch (Exception e) {
            return ToolResult.error("btca execution failed: " + e.getMessage());
        }
    }

    private String context7ApiKey;

    public void setContext7ApiKey(String key) {
        this.context7ApiKey = key;
    }

    private ToolResult handleDocsLookup(Map<String, Object> params, ToolPermission permission) {
        String library = (String) params.get("library");
        String query = (String) params.get("query");
        if (library == null || query == null) return ToolResult.error("Missing library or query parameter");

        if (context7ApiKey == null || context7ApiKey.isBlank()) {
            return ToolResult.ok("[Context7 API key not configured. Set context7.api.key in application.yml]");
        }

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            ObjectMapper mapper = new ObjectMapper();

            String resolveUrl = "https://context7.com/api/resolve-library-id?library=" +
                    java.net.URLEncoder.encode(library, "UTF-8") + "&query=" +
                    java.net.URLEncoder.encode(query, "UTF-8");
            java.net.http.HttpRequest resolveReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(resolveUrl))
                    .header("Authorization", "Bearer " + context7ApiKey)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resolveResp = client.send(resolveReq,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (resolveResp.statusCode() != 200) {
                return ToolResult.error("Context7 library resolution failed: HTTP " + resolveResp.statusCode());
            }

            JsonNode resolveJson = mapper.readTree(resolveResp.body());
            String libraryId;
            if (resolveJson.isArray() && resolveJson.size() > 0) {
                libraryId = resolveJson.get(0).path("libraryId").asText(null);
            } else {
                libraryId = resolveJson.path("libraryId").asText(null);
            }
            if (libraryId == null) {
                return ToolResult.error("Library '" + library + "' not found in Context7");
            }

            String docsUrl = "https://context7.com/api/query-docs?libraryId=" +
                    java.net.URLEncoder.encode(libraryId, "UTF-8") + "&query=" +
                    java.net.URLEncoder.encode(query, "UTF-8");
            java.net.http.HttpRequest docsReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(docsUrl))
                    .header("Authorization", "Bearer " + context7ApiKey)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> docsResp = client.send(docsReq,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (docsResp.statusCode() != 200) {
                return ToolResult.error("Context7 docs query failed: HTTP " + docsResp.statusCode());
            }

            return ToolResult.ok(docsResp.body());
        } catch (Exception e) {
            return ToolResult.error("Context7 lookup failed: " + e.getMessage());
        }
    }

    private ToolResult handleSkillImport(Map<String, Object> params, ToolPermission permission) {
        String url = (String) params.get("url");
        String source = (String) params.get("source");
        if (url == null || url.isBlank()) return ToolResult.error("Missing url parameter");

        if (memPalaceClient == null || !memPalaceClient.isEnabled()) {
            return ToolResult.error("MemPalace not enabled. Set axolotl.mempalace.enabled=true");
        }

        try {
            Map<String, Object> skillData = memPalaceClient.importSkill(url, source != null ? source : "opencode");
            String name = (String) skillData.getOrDefault("name", "unknown");
            String result = String.format(
                    "Imported: %s (source=%s)",
                    name, source != null ? source : "opencode");
            return ToolResult.ok(result);
        } catch (Exception e) {
            return ToolResult.error("Skill import failed: " + e.getMessage());
        }
    }

    @FunctionalInterface
    public interface ToolExecutorHandler {
        ToolResult execute(Map<String, Object> params, ToolPermission permission);
    }

    public PendingQuestion getPendingQuestion(String schemaId) {
        return pendingQuestions.get(schemaId);
    }

    public PendingQuestion answerQuestion(String schemaId, String answer) {
        PendingQuestion pq = pendingQuestions.get(schemaId);
        if (pq != null) {
            pq.setAnswer(answer);
        }
        return pq;
    }

    public void clearPendingQuestion(String schemaId) {
        pendingQuestions.remove(schemaId);
    }

    public boolean hasPendingQuestion(String schemaId) {
        PendingQuestion pq = pendingQuestions.get(schemaId);
        return pq != null && !pq.hasAnswer();
    }
}