package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolCategory;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.ProjectType;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.agent.orchestrator.llm.LlmService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.*;
import java.time.Instant;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class ToolExecutorImpl implements ToolExecutor {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolExecutorHandler> handlers = new ConcurrentHashMap<>();
    private LlmService llmService;
    private ExecutionWebSocketHandler webSocketHandler;
    private ExecutionStateManager stateManager;
    private Driver neo4jDriver;

    private static final String DELETION_MARKER_FILE = "/Users/Shared/Axolotl/deleted_files.json";

    private static final Set<String> DEFAULT_ALLOWED_COMMANDS = Set.of(
        "ls", "cat", "grep", "find", "git", "cd", "pwd", "echo", "head", "tail",
        "wc", "sort", "uniq", "diff", "patch", "mkdir", "cp", "mv", "rm", "touch",
        "chmod", "date", "env", "which", "dirname", "basename", "readlink",
        "xargs", "cut", "tr", "sed", "awk", "printf", "tee", "zip", "unzip",
        "tar", "gzip", "gunzip", "make", "mvn", "npm", "node", "python3", "python",
        "curl", "wget", "ping", "nslookup", "dig", "ssh", "scp", "rsync",
        "flutter", "dart", "pub"
    );

    public ToolExecutorImpl() {
        registerDefaultTools();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ToolExecutorImpl(LlmService llmService,
                            ExecutionWebSocketHandler webSocketHandler,
                            ExecutionStateManager stateManager,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            Driver neo4jDriver) {
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.stateManager = stateManager;
        this.neo4jDriver = neo4jDriver;
        registerDefaultTools();
    }

    private void registerDefaultTools() {
        registerTool(new Tool("file_read", "Read File", "Read contents of a file", """
            {"type":"object","properties":{"path":{"type":"string","description":"Absolute path to file"}},"required":["path"]}
            """, ToolCategory.FILE_SYSTEM));

        registerTool(new Tool("file_write", "Write File", "Write content to a file", """
            {"type":"object","properties":{"path":{"type":"string","description":"Absolute path to file"},"content":{"type":"string","description":"Content to write"}},"required":["path","content"]}
            """, ToolCategory.FILE_SYSTEM));
        registerTool(new Tool("write_file", "Write File", "Write content to a file (alias for file_write)", """
            {"type":"object","properties":{"path":{"type":"string","description":"Absolute path to file"},"content":{"type":"string","description":"Content to write"}},"required":["path","content"]}
            """, ToolCategory.FILE_SYSTEM));
        registerTool(new Tool("create_file", "Create File", "Write content to a new file (alias for file_write)", """
            {"type":"object","properties":{"path":{"type":"string","description":"Absolute path to file"},"content":{"type":"string","description":"Content to write"}},"required":["path","content"]}
            """, ToolCategory.FILE_SYSTEM));
        registerTool(new Tool("save_file", "Save File", "Save content to a file (alias for file_write)", """
            {"type":"object","properties":{"path":{"type":"string","description":"Absolute path to file"},"content":{"type":"string","description":"Content to write"}},"required":["path","content"]}
            """, ToolCategory.FILE_SYSTEM));

        registerTool(new Tool("directory_read", "List Directory", "List files in a directory", """
            {"type":"object","properties":{"path":{"type":"string","description":"Absolute path to directory"}},"required":["path"]}
            """, ToolCategory.FILE_SYSTEM));

        registerTool(new Tool("bash", "Bash", "Execute a bash command", """
            {"type":"object","properties":{"command":{"type":"string","description":"The bash command to execute"},"cwd":{"type":"string","description":"Working directory (optional)"},"timeout":{"type":"integer","description":"Timeout in seconds (default 30)"}},"required":["command"]}
            """, ToolCategory.EXECUTION));
        registerTool(new Tool("execute_command", "Execute Command", "Execute a bash command (alias for bash)", """
            {"type":"object","properties":{"command":{"type":"string","description":"The command to execute"},"cwd":{"type":"string","description":"Working directory (optional)"},"timeout":{"type":"integer","description":"Timeout in seconds (default 30)"}},"required":["command"]}
            """, ToolCategory.EXECUTION));
        registerTool(new Tool("run_command", "Run Command", "Execute a bash command (alias for bash)", """
            {"type":"object","properties":{"command":{"type":"string","description":"The command to execute"},"cwd":{"type":"string","description":"Working directory (optional)"},"timeout":{"type":"integer","description":"Timeout in seconds (default 30)"}},"required":["command"]}
            """, ToolCategory.EXECUTION));
        registerTool(new Tool("exec_command", "Exec Command", "Execute a bash command (alias for bash)", """
            {"type":"object","properties":{"command":{"type":"string","description":"The command to execute"},"cwd":{"type":"string","description":"Working directory (optional)"},"timeout":{"type":"integer","description":"Timeout in seconds (default 30)"}},"required":["command"]}
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

        registerTool(new Tool("build_app", "Build App", "Build a mobile app project and report missing dependencies", """
            {"type":"object","properties":{"projectPath":{"type":"string","description":"Project directory path (default: schema target path)"}},"required":[]}
            """, ToolCategory.EXECUTION));

        handlers.put("file_read", this::handleFileRead);
        handlers.put("file_write", this::handleFileWrite);
        handlers.put("write_file", this::handleFileWrite);
        handlers.put("create_file", this::handleFileWrite);
        handlers.put("save_file", this::handleFileWrite);
        handlers.put("directory_read", this::handleDirectoryRead);
        handlers.put("bash", this::handleBash);
        handlers.put("execute_command", this::handleBash);
        handlers.put("run_command", this::handleBash);
        handlers.put("exec_command", this::handleBash);
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
        // build_app handled directly in 6-param execute() to access schemaId/nodeId
    }

    @Override
    public void registerTool(Tool tool) {
        tools.put(tool.getId(), tool);
    }

    /**
     * Register a handler for a dynamic/plugin tool.
     * Plugin tools use this instead of the static handlers registered in registerDefaultTools().
     */
    @Override
    public void registerPluginHandler(String toolId, ToolExecutorHandler handler) {
        handlers.put(toolId, handler);
    }

    @Override
    public Tool getTool(String toolId) {
        return tools.get(toolId);
    }

    @Override
    public Map<String, Tool> getAllTools() {
        return Collections.unmodifiableMap(tools);
    }

    @Override
    public List<Tool> getToolsByCategory(ToolCategory category) {
        return tools.values().stream()
            .filter(t -> t.getCategory() == category)
            .collect(Collectors.toList());
    }

    @Override
    public ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission) {
        return execute(toolId, params, permission, null, null);
    }

    @Override
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

    private ToolResult handleFileRead(Map<String, Object> params, ToolPermission permission) {
        String path = (String) params.get("path");
        if (path == null) path = (String) params.get("file_path");
        if (path == null) path = (String) params.get("filePath");
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
        if (path == null) path = (String) params.get("file_path");
        if (path == null) path = (String) params.get("filePath");
        String content = (String) params.get("content");
        if (content == null) content = (String) params.get("code");
        if (content == null) content = (String) params.get("body");
        if (content == null) content = (String) params.get("data");
        if (path == null || content == null) return ToolResult.error("Missing path or content");

        try {
            // Sandbox validation: reject writes outside allowedPaths (if configured)
            validateSandboxPath(path, permission, null);
            java.nio.file.Path targetPath = Path.of(path);
            Files.createDirectories(targetPath.getParent());
            Files.writeString(targetPath, content);
            return ToolResult.ok("File written: " + path);
        } catch (SecurityException e) {
            return ToolResult.error(e.getMessage());
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

        String cmdName = command.trim().split("\\s+")[0];
        if (!DEFAULT_ALLOWED_COMMANDS.contains(cmdName)) {
            return ToolResult.error("Command not allowed: " + cmdName + ". Allowed commands: " + DEFAULT_ALLOWED_COMMANDS);
        }

        if (command.trim().startsWith("rm ") || command.trim().equals("rm")) {
            return interceptRmCommand(command, cwd);
        }

        // Block command substitution — unambiguous arbitrary code execution vector
        if (command.contains("$(") || command.contains("`")) {
            return ToolResult.error("Command blocked: substitution ($(..) or backticks) not allowed");
        }

        // Validate each pipe segment's command is in the allowed set
        if (command.contains("|")) {
            String[] pipeline = command.split("\\|");
            for (String segment : pipeline) {
                segment = segment.trim();
                if (segment.isEmpty()) continue;
                String segCmd = segment.split("\\s+")[0];
                if (!DEFAULT_ALLOWED_COMMANDS.contains(segCmd)) {
                    return ToolResult.error("Pipe chain blocked: '" + segCmd + "' not in allowed set: " + DEFAULT_ALLOWED_COMMANDS);
                }
            }
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
        if (query == null) query = "";

        if (neo4jDriver == null) return ToolResult.error("Memory store (Neo4j) not available");

        try (var session = neo4jDriver.session()) {
            var result = session.run(
                "MATCH (m:AgentMemory) WHERE m.content CONTAINS $query " +
                "RETURN m.id, m.content, m.metadata, m.createdAt " +
                "ORDER BY m.createdAt DESC LIMIT $limit",
                Map.of("query", query, "limit", limit));
            List<String> memories = new ArrayList<>();
            while (result.hasNext()) {
                var record = result.next();
                String ts = record.get("m.createdAt").toString();
                if (ts.length() > 19) ts = ts.substring(0, 19).replace("T", " ");
                memories.add("[" + record.get("m.id").asString() + "] " + ts
                        + ": " + record.get("m.content").asString());
            }
            if (memories.isEmpty()) {
                return ToolResult.ok("No memories found" + (query.isEmpty() ? "" : " for: " + query));
            }
            return ToolResult.ok("Memories (" + memories.size() + "):\n" + String.join("\n", memories));
        } catch (Exception e) {
            return ToolResult.error("Memory read failed: " + e.getMessage());
        }
    }

    private ToolResult handleMemoryWrite(Map<String, Object> params, ToolPermission permission) {
        String content = (String) params.get("content");
        Object metadata = params.get("metadata");
        if (content == null || content.isBlank()) return ToolResult.error("Missing content parameter");

        if (neo4jDriver == null) return ToolResult.error("Memory store (Neo4j) not available");

        try (var session = neo4jDriver.session()) {
            String id = java.util.UUID.randomUUID().toString();
            String metadataJson = "{}";
            if (metadata != null) {
                metadataJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata);
            }
            session.run(
                "CREATE (m:AgentMemory {id: $id, content: $content, metadata: $metadata, " +
                "createdAt: datetime(), updatedAt: datetime()})",
                Map.of("id", id, "content", content, "metadata", metadataJson));
            return ToolResult.ok("Memory saved with id: " + id);
        } catch (Exception e) {
            return ToolResult.error("Memory write failed: " + e.getMessage());
        }
    }

    private ToolResult handleWebSearch(Map<String, Object> params, ToolPermission permission) {
        String query = (String) params.get("query");
        Integer numResults = params.get("numResults") != null ? (Integer) params.get("numResults") : 5;
        if (query == null || query.isBlank()) return ToolResult.error("Missing query parameter");

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://lite.duckduckgo.com/lite/?q=" + encodedQuery + "&ia=web"))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (compatible; Axolotl-Bot)")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Parse DDG lite HTML for result links
            List<String> results = new ArrayList<>();
            Matcher matcher = Pattern.compile(
                    "<a[^>]+rel=\"nofollow\"[^>]+href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(response.body());
            int count = 0;
            while (matcher.find() && count < numResults) {
                String link = matcher.group(1).trim();
                String text = matcher.group(2).trim().replaceAll("\\s+", " ");
                if (!text.isEmpty() && !link.startsWith("#")) {
                    results.add(text + " — " + link);
                    count++;
                }
            }

            if (results.isEmpty()) {
                return ToolResult.ok("No search results found for: " + query);
            }
            return ToolResult.ok("Search results for '" + query + "':\n" + String.join("\n", results));
        } catch (Exception e) {
            return ToolResult.error("Web search failed: " + e.getMessage());
        }
    }

    private ToolResult handleWebFetch(Map<String, Object> params, ToolPermission permission) {
        String url = (String) params.get("url");
        if (url == null) return ToolResult.error("Missing url parameter");

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (compatible; Axolotl-Bot)")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return ToolResult.ok(response.body());
        } catch (Exception e) {
            return ToolResult.error("Web fetch failed: " + e.getMessage());
        }
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

            String result = llmService.chat("ollama", null, fullPrompt, null).text();
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
        if (query == null || query.isBlank()) {
            return ToolResult.error("Missing required parameter: query");
        }

        if (neo4jDriver == null) return ToolResult.error("Memory store (Neo4j) not available");

        try (var session = neo4jDriver.session()) {
            var result = session.run(
                "MATCH (m:AgentMemory) WHERE m.content CONTAINS $query " +
                "RETURN m.id, m.content, m.metadata, m.createdAt " +
                "ORDER BY m.createdAt DESC LIMIT $limit",
                Map.of("query", query, "limit", limit));
            List<String> memories = new ArrayList<>();
            while (result.hasNext()) {
                var record = result.next();
                String ts = record.get("m.createdAt").toString();
                if (ts.length() > 19) ts = ts.substring(0, 19).replace("T", " ");
                memories.add("[" + record.get("m.id").asString() + "] " + ts
                        + ": " + record.get("m.content").asString());
            }
            if (memories.isEmpty()) {
                return ToolResult.ok("No memories found for: " + query);
            }
            return ToolResult.ok("Memory search results (" + memories.size() + "):\n" + String.join("\n", memories));
        } catch (Exception e) {
            return ToolResult.error("Memory search failed: " + e.getMessage());
        }
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

    /**
     * Check if a given path is allowed within the sandbox (allowedPaths).
     */
    private String validateSandboxPath(String requestedPath, ToolPermission permission, String schemaTargetPath) {
        if (permission != null && permission.getAllowedPaths() != null && !permission.getAllowedPaths().isEmpty()) {
            boolean allowed = permission.getAllowedPaths().stream()
                    .anyMatch(p -> matchesGlob(requestedPath, p));
            if (allowed) return requestedPath;
            throw new SecurityException("File write blocked: path " + requestedPath
                    + " not in node's allowedPaths: " + permission.getAllowedPaths());
        }

        if (schemaTargetPath != null && !schemaTargetPath.isBlank()) {
            String normalizedRequested = Path.of(requestedPath).normalize().toAbsolutePath().toString().replace("\\", "/");
            String normalizedTarget = Path.of(schemaTargetPath).normalize().toAbsolutePath().toString().replace("\\", "/");
            if (!normalizedTarget.endsWith("/")) normalizedTarget += "/";
            if (normalizedRequested.startsWith(normalizedTarget)) {
                return normalizedRequested;
            }
            throw new SecurityException("File write blocked: path " + requestedPath
                    + " is outside schema targetPath: " + schemaTargetPath);
        }

        return requestedPath;
    }

    private boolean matchesGlob(String path, String pattern) {
        if (pattern == null || path == null) return false;
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", ".+?")
                .replace("*", "[^/]+");
        return path.matches(regex);
    }

    @Override
    public ToolResult handleFileReadWithSandbox(Map<String, Object> params, ToolPermission permission, String schemaTargetPath) {
        String path = (String) params.get("path");
        if (path == null) return ToolResult.error("Missing path parameter");

        if (schemaTargetPath != null && !schemaTargetPath.isBlank() && !path.startsWith("/")) {
            path = schemaTargetPath.replaceAll("/+$", "") + "/" + path;
        }

        try {
            validateSandboxPath(path, permission, schemaTargetPath);
        } catch (SecurityException e) {
            return ToolResult.error(e.getMessage());
        }

        try {
            String content = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            return ToolResult.ok(content);
        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    private ProjectType projectType = ProjectType.FLUTTER;

    private String runPostWriteValidator(String filePath) {
        if (!projectType.matchesExtension(filePath)) return "";
        var cmd = new java.util.ArrayList<>(projectType.getValidateCommand());
        cmd.add(filePath);
        try {
            var proc = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
            boolean finished = proc.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "[validator timeout]";
            }
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            if (proc.exitValue() != 0 && !out.isEmpty()) {
                return out;
            }
            return "";
        } catch (Exception e) {
            return "[validator error: " + e.getMessage() + "]";
        }
    }

    @Override
    public ToolResult handleFileWriteWithSandbox(Map<String, Object> params, ToolPermission permission,
                                                   String schemaTargetPath, String schemaId, String nodeId) {
        String path = (String) params.get("path");
        String content = (String) params.get("content");
        if (path == null || content == null) return ToolResult.error("Missing path or content");

        // Resolve relative paths against schema targetPath
        if (schemaTargetPath != null && !schemaTargetPath.isBlank() && !path.startsWith("/")) {
            path = schemaTargetPath.replaceAll("/+$", "") + "/" + path;
        }

        try {
            validateSandboxPath(path, permission, schemaTargetPath);
            java.nio.file.Path targetPath = java.nio.file.Path.of(path);
            boolean exists = java.nio.file.Files.exists(targetPath);

            // Diff-review mode: backup existing file before overwriting
            boolean requireDiff = Boolean.TRUE.equals(params.get("_diffReview"));
            String originalContent = null;
            String backupPath = null;
            if (requireDiff && exists) {
                originalContent = java.nio.file.Files.readString(targetPath);
                backupPath = path + ".axolotl.bak";
                java.nio.file.Files.copy(targetPath, java.nio.file.Path.of(backupPath),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Compute content hash before write for verification
            String newHash = contentHash(content);
            String oldHash = null;
            if (exists) {
                try {
                    oldHash = contentHash(java.nio.file.Files.readString(targetPath));
                } catch (Exception e) {
                    oldHash = "read-error";
                }
            }

            java.nio.file.Files.createDirectories(targetPath.getParent());
            java.nio.file.Files.writeString(targetPath, content);

            // Verify write: re-read and compare hash
            String writtenHash = null;
            String verifyStatus = "";
            try {
                String written = java.nio.file.Files.readString(targetPath);
                writtenHash = contentHash(written);
                if (!newHash.equals(writtenHash)) {
                    verifyStatus = " [WRITE MISMATCH - content hash differs from intended]";
                } else if (exists && oldHash != null && newHash.equals(oldHash)) {
                    verifyStatus = " [WRITE SKIPPED - content identical to existing]";
                } else {
                    verifyStatus = " [WRITE VERIFIED]";
                }
            } catch (IOException e) {
                verifyStatus = " [WRITE VERIFY FAILED - " + e.getMessage() + "]";
            }

            // Track file changes
            if (schemaId != null && nodeId != null) {
                stateManager.recordFileChange(schemaId, nodeId, path, exists ? "modified" : "created");
            }

            // Record pending diff for review
            if (requireDiff && exists && originalContent != null && schemaId != null && nodeId != null) {
                stateManager.addPendingDiff(schemaId, nodeId,
                        new ExecutionStateManager.PendingDiff(path, originalContent, content, backupPath));
            }

            String validation = runPostWriteValidator(path);
            if (validation.isEmpty()) {
                return ToolResult.ok("File written: " + path + verifyStatus);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("File written: ").append(path).append(verifyStatus).append("\n");
            sb.append("--- SYNTAX CHECK (").append(extensionOf(path)).append(") ---\n");
            sb.append(validation);
            return ToolResult.ok(sb.toString());
        } catch (SecurityException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    private static String contentHash(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot) : "";
    }

    @Override
    public ToolResult handleDirectoryReadWithSandbox(Map<String, Object> params, ToolPermission permission, String schemaTargetPath) {
        String path = (String) params.get("path");
        if (path == null) path = ".";

        if (schemaTargetPath != null && !schemaTargetPath.isBlank() && !path.startsWith("/")) {
            path = schemaTargetPath.replaceAll("/+$", "") + "/" + path;
        }

        try {
            validateSandboxPath(path, permission, schemaTargetPath);
        } catch (SecurityException e) {
            return ToolResult.error(e.getMessage());
        }

        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(java.nio.file.Path.of(path))) {
            java.util.List<String> files = stream.map(p -> p.toString()).sorted().collect(java.util.stream.Collectors.toList());
            return ToolResult.ok(String.join("\n", files));
        } catch (IOException e) {
            return ToolResult.error("Failed to list directory: " + e.getMessage());
        }
    }

    @Override
    public ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission,
                               String schemaId, String nodeId, String schemaTargetPath) {
        return execute(toolId, params, permission, schemaId, nodeId, schemaTargetPath, null);
    }

    @Override
    public ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission,
                               String schemaId, String nodeId, String schemaTargetPath,
                               String projectTypeStr) {
        if (projectTypeStr != null && !projectTypeStr.isBlank()) {
            this.projectType = ProjectType.fromString(projectTypeStr);
        }
        if ("file_write".equals(toolId) || "write_file".equals(toolId) || "create_file".equals(toolId) || "save_file".equals(toolId)) {
            return handleFileWriteWithSandbox(params, permission, schemaTargetPath, schemaId, nodeId);
        }
        if ("directory_read".equals(toolId)) {
            return handleDirectoryReadWithSandbox(params, permission, schemaTargetPath);
        }
        if ("file_read".equals(toolId)) {
            return handleFileReadWithSandbox(params, permission, schemaTargetPath);
        }
        if ("build_app".equals(toolId)) {
            return handleBuildApp(params, permission, schemaId, nodeId, schemaTargetPath);
        }
        return execute(toolId, params, permission, schemaId, nodeId);
    }

    private ToolResult handleBuildApp(Map<String, Object> params, ToolPermission permission,
                                      String schemaId, String nodeId, String schemaTargetPath) {
        String projectPath = (String) params.getOrDefault("projectPath", "");
        if (projectPath.isBlank()) projectPath = schemaTargetPath;
        if (projectPath == null || projectPath.isBlank()) projectPath = ".";

        java.nio.file.Path projDir = java.nio.file.Path.of(projectPath);
        String manifest = projectType.getManifestFile();
        boolean hasManifest = java.nio.file.Files.exists(projDir.resolve(manifest));

        var missing = new java.util.ArrayList<String>();
        var details = new StringBuilder();

        if (!hasManifest) {
            // Auto-scaffold Flutter project if it doesn't exist
            if (projectType == ProjectType.FLUTTER) {
                details.append("No ").append(manifest).append(" found — scaffolding new Flutter project...\n");
                String projectName = projDir.getFileName().toString()
                        .replaceAll("[^a-zA-Z0-9_]", "_")
                        .replaceAll("^[^a-zA-Z_]+", "app_")
                        .toLowerCase();
                try {
                    java.nio.file.Files.createDirectories(projDir);
                    var createCmd = new java.util.ArrayList<String>();
                    createCmd.add("flutter");
                    createCmd.add("create");
                    createCmd.add("--project-name");
                    createCmd.add(projectName);
                    createCmd.add(projDir.toString());
                    var pb = new ProcessBuilder(createCmd)
                            .redirectErrorStream(true);
                    var proc = pb.start();
                    boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
                    String createOutput = new String(proc.getInputStream().readAllBytes());
                    if (!finished || proc.exitValue() != 0) {
                        details.append("flutter create failed:\n").append(createOutput).append("\n");
                        return ToolResult.error(details.toString());
                    }
                    details.append("flutter create SUCCESS\n");
                    hasManifest = java.nio.file.Files.exists(projDir.resolve(manifest));
                    if (!hasManifest) {
                        details.append("flutter create completed but ").append(manifest).append(" not found\n");
                        return ToolResult.error(details.toString());
                    }
                } catch (Exception e) {
                    details.append("flutter create error: ").append(e.getMessage()).append("\n");
                    return ToolResult.error(details.toString());
                }
            } else {
                return ToolResult.error("No " + manifest + " found at " + projectPath
                        + " — not a " + projectType.getDisplayName() + " project");
            }
        }

        details.append("Project: ").append(projectPath).append(" (").append(projectType.getDisplayName()).append(")\n");
        details.append("Detected via: ").append(manifest).append("\n");

        // Determine build mode early to skip iOS-only checks for APK builds
        Object buildModeObj = params.getOrDefault("buildMode", "debug");
        String buildMode = buildModeObj instanceof String ? (String) buildModeObj : "debug";
        boolean needsIosTools = buildMode.toLowerCase().contains("ios")
                || buildMode.toLowerCase().contains("appbundle")
                || buildMode.toLowerCase().contains("release");

        // Check SDK availability per project type
        switch (projectType) {
            case FLUTTER:
                checkSdk(details, missing, "flutter", "Flutter SDK",
                        "brew install flutter");
                checkAndroidSdk(details, missing);
                // Xcode and CocoaPods only needed for iOS builds
                if (needsIosTools) {
                    checkXcode(details, missing);
                    checkCocoaPods(details, missing);
                } else {
                    checkXcode(details, null);  // warn only, not a blocker
                    checkCocoaPods(details, null);
                }
                break;
            case PYTHON:
                checkSdk(details, missing, "python3", "Python 3",
                        "brew install python");
                break;
            case WEB:
                checkSdk(details, missing, "node", "Node.js",
                        "brew install node");
                break;
            case GO:
                checkSdk(details, missing, "go", "Go",
                        "brew install go");
                break;
            case RUST:
                checkSdk(details, missing, "cargo", "Cargo/Rust",
                        "brew install rust");
                break;
        }

        if (!missing.isEmpty()) {
            StringBuilder report = new StringBuilder();
            report.append("=== BUILD ENVIRONMENT CHECK ===\n");
            report.append(details);
            report.append("\n⚠️ Missing dependencies (").append(missing.size()).append("):\n");
            for (int i = 0; i < missing.size(); i++) {
                report.append("  ").append(i + 1).append(". ").append(missing.get(i)).append("\n");
            }
            if (schemaId != null && webSocketHandler != null) {
                webSocketHandler.sendDepsNeeded(schemaId, nodeId, missing, projectPath);
            }
            return ToolResult.error(report.toString());
        }

        // All deps present — run build
        // (buildMode already resolved above for iOS tool checks)
        var buildResults = new java.util.ArrayList<String>();
        var buildErrors = new java.util.ArrayList<String>();

        details.append("\n→ Running ").append(String.join(" ", projectType.getBuildCommand())).append(" ...\n");
        try {
            var cmd = new java.util.ArrayList<>(projectType.getBuildCommand());
            var proc = new ProcessBuilder(cmd)
                .directory(projDir.toFile())
                .redirectErrorStream(true)
                .start();
            boolean finished = proc.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                buildResults.add("✗ " + String.join(" ", cmd) + " → TIMED OUT");
            } else {
                String output = new String(proc.getInputStream().readAllBytes());
                if (proc.exitValue() == 0) {
                    buildResults.add("✓ " + String.join(" ", cmd) + " → SUCCESS");
                } else {
                    buildResults.add("✗ " + String.join(" ", cmd) + " → FAILED");
                    buildErrors.add("Main build failed:\n" + output);
                }
            }
        } catch (Exception e) {
            buildResults.add("✗ Main build → ERROR: " + e.getMessage());
            buildErrors.add("Main build error: " + e.getMessage());
        }

        // For Flutter projects, run additional builds (appbundle, iOS)
        if (projectType == ProjectType.FLUTTER) {
            // AppBundle (requires ANDROID_HOME)
            if (System.getenv("ANDROID_HOME") != null && !System.getenv("ANDROID_HOME").isBlank()) {
                var appbundleCmd = java.util.List.of("flutter", "build", "appbundle", "--" + buildMode);
                details.append("→ Running ").append(String.join(" ", appbundleCmd)).append(" ...\n");
                try {
                    var pb = new ProcessBuilder(appbundleCmd)
                        .directory(projDir.toFile())
                        .redirectErrorStream(true);
                    var p = pb.start();
                    if (p.waitFor(300, TimeUnit.SECONDS)) {
                        String out = new String(p.getInputStream().readAllBytes());
                        if (p.exitValue() == 0) {
                            buildResults.add("✓ " + String.join(" ", appbundleCmd) + " → SUCCESS");
                        } else {
                            buildResults.add("✗ " + String.join(" ", appbundleCmd) + " → FAILED (non-blocking)");
                            buildErrors.add("AppBundle build output:\n" + out);
                        }
                    } else {
                        p.destroyForcibly();
                        buildResults.add("✗ " + String.join(" ", appbundleCmd) + " → TIMED OUT");
                    }
                } catch (Exception e) {
                    buildResults.add("✗ AppBundle build → ERROR: " + e.getMessage());
                }
            }

            // iOS build (macOS only, xcode-select -p must succeed)
            if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
                try {
                    var xcCheck = new ProcessBuilder("xcode-select", "-p")
                        .redirectErrorStream(true).start();
                    if (xcCheck.waitFor(5, TimeUnit.SECONDS) && xcCheck.exitValue() == 0) {
                        var iosCmd = java.util.List.of("flutter", "build", "ios", "--no-codesign", "--" + buildMode);
                        details.append("→ Running ").append(String.join(" ", iosCmd)).append(" ...\n");
                        try {
                            var pb = new ProcessBuilder(iosCmd)
                                .directory(projDir.toFile())
                                .redirectErrorStream(true);
                            var p = pb.start();
                            if (p.waitFor(300, TimeUnit.SECONDS)) {
                                String out = new String(p.getInputStream().readAllBytes());
                                if (p.exitValue() == 0) {
                                    buildResults.add("✓ " + String.join(" ", iosCmd) + " → SUCCESS");
                                } else {
                                    buildResults.add("✗ " + String.join(" ", iosCmd) + " → FAILED (non-blocking)");
                                }
                            } else {
                                p.destroyForcibly();
                                buildResults.add("✗ " + String.join(" ", iosCmd) + " → TIMED OUT");
                            }
                        } catch (Exception e) {
                            buildResults.add("✗ iOS build → ERROR: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    // xcode-select check failed — skip iOS build
                }
            }
        }

        StringBuilder finalResult = new StringBuilder();
        if (!buildErrors.isEmpty()) {
            finalResult.append("=== BUILD RESULTS WITH ERRORS ===\n");
        } else {
            finalResult.append("=== BUILD RESULTS ===\n");
        }
        finalResult.append("Project: ").append(projectPath).append("\n");
        for (String r : buildResults) {
            finalResult.append(r).append("\n");
        }
        if (!buildErrors.isEmpty()) {
            finalResult.append("\nErrors:\n");
            for (String e : buildErrors) {
                finalResult.append(e).append("\n=====\n");
            }
        }
        return buildErrors.isEmpty() && buildResults.stream().anyMatch(r -> r.startsWith("✓ "))
            ? ToolResult.ok(finalResult.toString())
            : ToolResult.error(finalResult.toString());
    }

    private void checkSdk(StringBuilder details, java.util.ArrayList<String> missing,
                           String binary, String name, String installHint) {
        details.append("[").append(name).append("] ");
        try {
            var proc = new ProcessBuilder("which", binary)
                .redirectErrorStream(true).start();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.exitValue() == 0) {
                String path = new String(proc.getInputStream().readAllBytes()).trim();
                details.append("✅ ").append(path).append("\n");
            } else {
                details.append("❌ Not found\n");
                missing.add(name + " — install via: " + installHint);
            }
        } catch (Exception e) {
            details.append("❌ Check failed: ").append(e.getMessage()).append("\n");
            missing.add(name + " — install via: " + installHint);
        }
    }

    private void checkAndroidSdk(StringBuilder details, java.util.ArrayList<String> missing) {
        String androidHome = System.getenv("ANDROID_HOME");
        if (androidHome != null && !androidHome.isBlank()) {
            details.append("[Android SDK] ✅ ").append(androidHome).append("\n");
            // Also check that the SDK path actually has content
            var sdkDir = java.nio.file.Path.of(androidHome);
            if (!java.nio.file.Files.exists(sdkDir.resolve("platforms"))
                    && !java.nio.file.Files.exists(sdkDir.resolve("cmdline-tools"))
                    && !java.nio.file.Files.exists(sdkDir.resolve("tools"))) {
                details.append("  ⚠️  ANDROID_HOME set but SDK not fully installed (no platforms/ directory)\n");
                missing.add("Android SDK components — run: sdkmanager \"platform-tools\" \"platforms;android-33\"");
            }
            return;
        }

        // Scan common Android SDK install paths
        String[] commonPaths = {
            System.getProperty("user.home") + "/Library/Android/sdk",
            System.getProperty("user.home") + "/Android/Sdk",
            "/usr/local/share/android-sdk",
            "/opt/android-sdk"
        };
        for (String p : commonPaths) {
            var dir = java.nio.file.Path.of(p);
            if (java.nio.file.Files.exists(dir.resolve("platforms"))
                    || java.nio.file.Files.exists(dir.resolve("cmdline-tools"))
                    || java.nio.file.Files.exists(dir.resolve("tools"))) {
                details.append("[Android SDK] ✅ Found at ").append(p)
                       .append(" (export ANDROID_HOME=\"" + p + "\" for tools that need it)\n");
                // Warning only — Flutter build works via its own config
                return;
            }
        }

        // Try flutter config --machine to find the SDK path
        try {
            var fcProc = new ProcessBuilder("flutter", "config", "--machine")
                .redirectErrorStream(true).start();
            if (fcProc.waitFor(10, TimeUnit.SECONDS) && fcProc.exitValue() == 0) {
                String fcOut = new String(fcProc.getInputStream().readAllBytes()).trim();
                // Parse JSON: {"android-sdk": "/path/..."}
                if (fcOut.contains("android-sdk")) {
                    int idx = fcOut.indexOf("android-sdk");
                    int valStart = fcOut.indexOf('"', idx + 13) + 1;
                    int valEnd = fcOut.indexOf('"', valStart);
                    if (valStart > 0 && valEnd > valStart) {
                        String flutterSdk = fcOut.substring(valStart, valEnd);
                        details.append("[Android SDK] ⚠️  Flutter knows ").append(flutterSdk)
                               .append(" but ANDROID_HOME not set (warning, flutter build works)\n");
                        // Warning only — Flutter finds the SDK via its own config
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // flutter config failed — continue to fallback
        }

        details.append("[Android SDK] ❌ Not found\n");
        missing.add("Android SDK — install Android Studio, then run: flutter config --android-sdk ~/Library/Android/sdk");
    }

    private void checkXcode(StringBuilder details, java.util.ArrayList<String> missing) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        details.append("[Xcode] ");
        try {
            var proc = new ProcessBuilder("xcode-select", "-p")
                .redirectErrorStream(true).start();
            proc.waitFor(5, TimeUnit.SECONDS);

            java.nio.file.Path xcodePath = java.nio.file.Path.of("/Applications/Xcode.app");
            boolean hasFullXcode = java.nio.file.Files.isDirectory(xcodePath);

            if (proc.exitValue() == 0) {
                String xp = new String(proc.getInputStream().readAllBytes()).trim();
                if (xp.contains("CommandLineTools")) {
                    details.append("⚠️  CLI tools only (").append(xp).append(")\n");
                    if (hasFullXcode) {
                        details.append("  → Full Xcode.app found! Run: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer\n");
                        if (missing != null) missing.add("Xcode not active — run: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer");
                    } else {
                        details.append("  → Full Xcode.app missing. Install from Mac App Store:\n");
                        details.append("    https://apps.apple.com/app/xcode/id497799835\n");
                        if (missing != null) missing.add("Full Xcode.app required — install from Mac App Store: https://apps.apple.com/app/xcode/id497799835");
                    }
                } else {
                    String ver = "?";
                    try {
                        var vProc = new ProcessBuilder("xcodebuild", "-version")
                            .redirectErrorStream(true).start();
                        if (vProc.waitFor(5, TimeUnit.SECONDS) && vProc.exitValue() == 0) {
                            ver = new String(vProc.getInputStream().readAllBytes()).trim().lines().findFirst().orElse("?");
                        }
                    } catch (Exception ignored) {}
                    details.append("✅ ").append(xp).append(" (v").append(ver).append(")\n");
                }
            } else {
                if (hasFullXcode) {
                    details.append("⚠️  Xcode.app found but not activated\n");
                    details.append("  → Run: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer\n");
                    if (missing != null) missing.add("Xcode not activated — run: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer");
                } else {
                    details.append("❌ Not found\n");
                    if (missing != null) missing.add("Xcode — install from Mac App Store: https://apps.apple.com/app/xcode/id497799835");
                }
            }
        } catch (Exception e) {
            details.append("❌ Check failed: ").append(e.getMessage()).append("\n");
            if (missing != null) missing.add("Xcode — install from Mac App Store: https://apps.apple.com/app/xcode/id497799835");
        }
    }

    private void checkCocoaPods(StringBuilder details, java.util.ArrayList<String> missing) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        details.append("[CocoaPods] ");
        try {
            var proc = new ProcessBuilder("which", "pod")
                .redirectErrorStream(true).start();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.exitValue() == 0) {
                String path = new String(proc.getInputStream().readAllBytes()).trim();
                details.append("✅ ").append(path).append("\n");
            } else {
                details.append("❌ Not found (only needed for iOS builds)\n");
                if (missing != null) missing.add("CocoaPods — install via: sudo gem install cocoapods");
            }
        } catch (Exception e) {
            details.append("❌ Check failed: ").append(e.getMessage()).append("\n");
            if (missing != null) missing.add("CocoaPods — install via: sudo gem install cocoapods");
        }
    }
}