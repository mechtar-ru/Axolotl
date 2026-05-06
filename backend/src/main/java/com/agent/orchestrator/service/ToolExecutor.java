package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolCategory;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.agent.orchestrator.llm.LlmService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;
import java.time.Instant;

@Service
public class ToolExecutor {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolExecutorHandler> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private LlmService llmService;
    private Driver neo4jDriver;

    private ExecutionWebSocketHandler webSocketHandler;

    private static final String DELETION_MARKER_FILE = "/Users/Shared/Axolotl/deleted_files.json";

    private static final Set<String> DEFAULT_BLOCKED_COMMANDS = Set.of(
        "rm -rf", "rm -r /", "del /", "format",
        "mkfs", "dd if=", "> /dev/sd", ":(){",
        "curl | sh", "wget | sh", "bash -i", "python -m SimpleHTTPServer",
        "nc -e", "/bin/sh -i", "nohup ", "pkill -9",
        "chmod 777 /", "chown -R", "fdisk", "parted"
    );

    public ToolExecutor() {
        registerDefaultTools();
    }

    public void setNeo4jDriver(Driver driver) {
        this.neo4jDriver = driver;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNeo4jDriverAuto(Driver driver) {
        this.neo4jDriver = driver;
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

        registerTool(new Tool("grep", "Grep", "Search for pattern in files (regex)", """
            {"type":"object","properties":{"pattern":{"type":"string","description":"Regex pattern"},"path":{"type":"string","description":"Directory to search"},"include":{"type":"string","description":"File pattern (e.g. *.java)"}},"required":["pattern","path"]}
            """, ToolCategory.FILE_SYSTEM));

        registerTool(new Tool("git", "Git", "Execute git commands", """
            {"type":"object","properties":{"command":{"type":"string","description":"Git command (status, diff, log, etc.)"},"repoPath":{"type":"string","description":"Repository path"}},"required":["command"]}
            """, ToolCategory.EXECUTION));

        registerTool(new Tool("memory_search", "Memory Search", "Search memory store by keywords", """
            {"type":"object","properties":{"query":{"type":"string","description":"Search query"},"limit":{"type":"number","description":"Max results"}},"required":["query"]}
            """, ToolCategory.MEMORY));

        registerTool(new Tool("web_api", "Web API", "Call REST API with JSON", """
            {"type":"object","properties":{"url":{"type":"string","description":"API endpoint URL"},"method":{"type":"string","description":"HTTP method (GET, POST, etc.)"},"headers":{"type":"object","description":"Request headers"},"body":{"type":"object","description":"Request body"}},"required":["url"]}
            """, ToolCategory.HTTP));

        registerTool(new Tool("graph_query", "Graph Query", "Query Neo4j code graph", """
            {"type":"object","properties":{"query":{"type":"string","description":"Cypher query or natural language"},"type":{"type":"string","description":"Query type (search, curate, impact)"}},"required":["query"]}
            """, ToolCategory.GRAPH));

        registerTool(new Tool("mcp_execute", "MCP Execute", "Execute MCP tool", """
            {"type":"object","properties":{"server":{"type":"string","description":"MCP server name"},"tool":{"type":"string","description":"Tool name"},"args":{"type":"object","description":"Tool arguments"}},"required":["server","tool"]}
            """, ToolCategory.MCP));

        handlers.put("file_read", this::handleFileRead);
        handlers.put("file_write", this::handleFileWrite);
        handlers.put("directory_read", this::handleDirectoryRead);
        handlers.put("bash", this::handleBash);
        handlers.put("memory_read", this::handleMemoryRead);
        handlers.put("memory_write", this::handleMemoryWrite);
        handlers.put("web_search", this::handleWebSearch);
        handlers.put("web_fetch", this::handleWebFetch);
        handlers.put("rlm_predict", this::handleRlmPredict);
        handlers.put("grep", this::handleGrep);
        handlers.put("git", this::handleGit);
        handlers.put("memory_search", this::handleMemorySearch);
        handlers.put("web_api", this::handleWebApi);
        handlers.put("graph_query", this::handleGraphQuery);
        handlers.put("mcp_execute", this::handleMcpExecute);
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

    public void setWebSocketHandler(ExecutionWebSocketHandler handler) {
        this.webSocketHandler = handler;
    }

    public void setLlmService(LlmService llmService) {
        this.llmService = llmService;
    }

    private ToolResult handleFileRead(Map<String, Object> params, ToolPermission permission) {
        String path = (String) params.get("path");
        if (path == null) return ToolResult.error("Missing path parameter");

        try {
            String content = Files.readString(Path.of(path));
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
            Files.writeString(Path.of(path), content);
            return ToolResult.ok("File written: " + path);
        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    private ToolResult handleDirectoryRead(Map<String, Object> params, ToolPermission permission) {
        String path = (String) params.get("path");
        if (path == null) path = ".";

        try (Stream<Path> stream = Files.list(Path.of(path))) {
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

        if (command.trim().startsWith("rm ") || command.trim().equals("rm")) {
            return interceptRmCommand(command, cwd);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            if (cwd != null) pb.directory(new File(cwd));
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

    private ToolResult interceptRmCommand(String command, String cwd) {
        try {
            String[] parts = command.split("\\s+");
            List<String> files = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i];
                if (p.startsWith("-")) continue;
                String fullPath = (cwd != null ? cwd + "/" : "") + p;
                files.add(fullPath.replaceAll("/+", "/"));
            }

            if (files.isEmpty()) {
                return ToolResult.error("No files specified for rm");
            }

            List<Map<String, Object>> deletions = new ArrayList<>();
            try {
                if (Files.exists(Paths.get(DELETION_MARKER_FILE))) {
                    String existing = Files.readString(Paths.get(DELETION_MARKER_FILE));
                    deletions = new com.fasterxml.jackson.databind.ObjectMapper().readValue(existing,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                }
            } catch (Exception e) { /* start fresh */ }

            for (String file : files) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("filePathInProject", file);
                entry.put("timestampFlaggedToDelete", Instant.now().toString());
                entry.put("reason", "Marked via rm command intercept");
                deletions.add(entry);
            }

            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(deletions);
            Files.writeString(Paths.get(DELETION_MARKER_FILE), json);
            return ToolResult.ok("Files marked for deletion (not deleted): " + files + ". See " + DELETION_MARKER_FILE);
        } catch (Exception e) {
            return ToolResult.error("Failed to mark files for deletion: " + e.getMessage());
        }
    }

    private ToolResult handleMemoryRead(Map<String, Object> params, ToolPermission permission) {
        String query = (String) params.get("query");
        Integer limit = params.get("limit") != null ? (Integer) params.get("limit") : 5;

        return ToolResult.ok("[Memory read placeholder - implement with actual storage]");
    }

    private ToolResult handleMemoryWrite(Map<String, Object> params, ToolPermission permission) {
        String content = (String) params.get("content");
        if (content == null) return ToolResult.error("Missing content parameter");

        return ToolResult.ok("[Memory write placeholder - implement with actual storage]");
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

        if (pattern == null || path == null) {
            return ToolResult.error("Missing required parameters: pattern, path");
        }

        if (pattern.matches(".*[`$\\(\\)\\{\\}\\|;&<>].*")) {
            return ToolResult.error("Pattern contains shell metacharacters.");
        }

        if (!path.matches("^[a-zA-Z0-9_\\-\\./]+$")) {
            return ToolResult.error("Path contains invalid characters.");
        }

        String includeArg = include != null && include.matches("^[a-zA-Z0-9_\\-\\.*]+$")
            ? " --include=" + include
            : "";
        String cmd = "grep -rE" + includeArg + " \"" + pattern + "\" " + path + " 2>/dev/null | head -50";
        return handleBash(Map.of("command", cmd, "timeout", 30), permission);
    }

    private ToolResult handleGit(Map<String, Object> params, ToolPermission permission) {
        String command = (String) params.get("command");
        String repoPath = (String) params.get("repoPath");

        if (command == null) {
            return ToolResult.error("Missing required parameter: command");
        }

        if (command.matches(".*[`$\\(\\)\\{\\}\\|;&<>].*")) {
            return ToolResult.error("Invalid git command: contains shell metacharacters.");
        }

        String safePath = repoPath != null && repoPath.matches("^[a-zA-Z0-9_\\-\\.\\/]+$")
            ? repoPath
            : ".";
        String fullCmd = "cd " + safePath + " && git " + command;
        return handleBash(Map.of("command", fullCmd, "timeout", 30), permission);
    }

    private ToolResult handleMemorySearch(Map<String, Object> params, ToolPermission permission) {
        String query = (String) params.get("query");
        Integer limit = params.get("limit") != null ? (Integer) params.get("limit") : 10;

        if (query == null) {
            return ToolResult.error("Missing required parameter: query");
        }

        return ToolResult.ok("[Memory search: " + query + " (memory service not available - stub only)]");
    }

    private ToolResult handleWebApi(Map<String, Object> params, ToolPermission permission) {
        String url = (String) params.get("url");
        String method = params.get("method") != null ? (String) params.get("method") : "GET";
        Object body = params.get("body");
        Object headers = params.get("headers");

        if (url == null) {
            return ToolResult.error("Missing required parameter: url");
        }

        return ToolResult.ok("[Web API call: " + method + " " + url + " - implement with HTTP client]");
    }

    private ToolResult handleGraphQuery(Map<String, Object> params, ToolPermission permission) {
        String query = (String) params.get("query");
        String type = params.get("type") != null ? (String) params.get("type") : "search";

        if (query == null) {
            return ToolResult.error("Missing required parameter: query");
        }

        if (neo4jDriver == null) {
            return ToolResult.error("Neo4j driver not configured");
        }

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(query);
            List<String> rows = new ArrayList<>();
            while (result.hasNext()) {
                var record = result.next();
                StringBuilder sb = new StringBuilder();
                for (var key : record.keys()) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(key).append(": ").append(record.get(key));
                }
                rows.add(sb.toString());
            }

            if (rows.isEmpty()) {
                return ToolResult.ok("No results found for query: " + query);
            }
            return ToolResult.ok("Results (" + rows.size() + "):\n" + String.join("\n", rows));
        } catch (Exception e) {
            return ToolResult.error("Graph query failed: " + e.getMessage());
        }
    }

    private ToolResult handleMcpExecute(Map<String, Object> params, ToolPermission permission) {
        String server = (String) params.get("server");
        String tool = (String) params.get("tool");
        Object args = params.get("args");

        if (server == null || tool == null) {
            return ToolResult.error("Missing required parameters: server, tool");
        }

        return ToolResult.ok("[MCP execute: " + server + ":" + tool + " - implement with MCP client]");
    }

    @FunctionalInterface
    public interface ToolExecutorHandler {
        ToolResult execute(Map<String, Object> params, ToolPermission permission);
    }
}