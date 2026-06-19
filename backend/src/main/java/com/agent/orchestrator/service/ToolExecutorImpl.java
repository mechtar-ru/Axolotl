package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolCategory;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.ProjectType;
import com.agent.orchestrator.config.AppConfig;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.agent.orchestrator.llm.LlmService;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.*;

/**
 * Tool executor with registration, dispatch, and sandbox-aware wrappers.
 * Handler implementations are delegated to {@link ToolHandlerService}.
 */
@Service
public class ToolExecutorImpl implements ToolExecutor {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolExecutorHandler> handlers = new ConcurrentHashMap<>();
    private final ToolHandlerService handlerService;
    private final BuildToolHandler buildToolHandler;
    private final AppConfig appConfig;
    private LlmService llmService;
    private ExecutionWebSocketHandler webSocketHandler;
    private ExecutionStateManager stateManager;
    private Driver neo4jDriver;

    // ── Package-visible for ToolHandlerService ──

    static final Set<String> DEFAULT_ALLOWED_COMMANDS = Set.of(
        "cat", "ls", "find", "grep", "flutter", "dart", "pub",
        "cargo", "rustc", "npm", "npx", "bun", "git", "make", "go",
        "head", "tail", "sort", "wc", "echo", "cp", "mv", "mkdir", "touch", "date",
        "cd", "pwd", "which", "dirname", "basename", "readlink",
        "unzip", "zip", "tar", "gzip", "gunzip", "diff", "patch",
        "cut", "tr", "printf", "tee", "env", "xargs", "uniq", "mvn"
    );

    public ToolExecutorImpl() {
        this.handlerService = new ToolHandlerService(null, null, null, null, null);
        this.buildToolHandler = new BuildToolHandler();
        this.appConfig = new AppConfig();
        registerDefaultTools();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ToolExecutorImpl(LlmService llmService,
                            ExecutionWebSocketHandler webSocketHandler,
                            ExecutionStateManager stateManager,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            Driver neo4jDriver,
                            ToolHandlerService handlerService,
                            BuildToolHandler buildToolHandler,
                            AppConfig appConfig) {
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.stateManager = stateManager;
        this.neo4jDriver = neo4jDriver;
        this.handlerService = handlerService;
        this.buildToolHandler = buildToolHandler;
        this.appConfig = appConfig;
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

        registerTool(new Tool("ask_planner", "Ask Planner",
            "Ask the senior architect model for guidance on architecture, code structure, complete code generation, or gap analysis. Pass a detailed prompt describing exactly what you need.", """
            {"type":"object","properties":{"prompt":{"type":"string","description":"Detailed question or task for the architect model"}},"required":["prompt"]}
            """, ToolCategory.EXECUTION));

        // Register handlers from ToolHandlerService
        if (handlerService != null) {
            handlers.put("file_read", handlerService::handleFileRead);
            handlers.put("file_write", handlerService::handleFileWrite);
            handlers.put("write_file", handlerService::handleFileWrite);
            handlers.put("create_file", handlerService::handleFileWrite);
            handlers.put("save_file", handlerService::handleFileWrite);
            handlers.put("directory_read", handlerService::handleDirectoryRead);
            handlers.put("bash", handlerService::handleBash);
            handlers.put("execute_command", handlerService::handleBash);
            handlers.put("run_command", handlerService::handleBash);
            handlers.put("exec_command", handlerService::handleBash);
            handlers.put("memory_read", handlerService::handleMemoryRead);
            handlers.put("memory_write", handlerService::handleMemoryWrite);
            handlers.put("web_search", handlerService::handleWebSearch);
            handlers.put("web_fetch", handlerService::handleWebFetch);
            handlers.put("rlm_predict", handlerService::handleRlmPredict);
            handlers.put("grep", handlerService::handleGrep);
            handlers.put("git", handlerService::handleGit);
            handlers.put("memory_search", handlerService::handleMemorySearch);
            handlers.put("web_api", handlerService::handleWebApi);
            handlers.put("graph_query", handlerService::handleGraphQuery);
            handlers.put("mcp_execute", handlerService::handleMcpExecute);
        }
        // build_app handled directly in 6-param execute() to access schemaId/nodeId
    }

    @Override
    public void registerTool(Tool tool) {
        tools.put(tool.getId(), tool);
    }

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
    public void unregisterTool(String toolId) {
        tools.remove(toolId);
    }

    @Override
    public void unregisterPluginHandler(String toolId) {
        handlers.remove(toolId);
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
                String argsJson = params != null ? sanitizeParams(params) : "";
                webSocketHandler.sendToolCall(schemaId, nodeId, toolId, argsJson, durationMs, result.isSuccess(), result.getOutput());
            }

            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            if (schemaId != null && nodeId != null && webSocketHandler != null) {
                webSocketHandler.sendToolCall(schemaId, nodeId, toolId, params != null ? sanitizeParams(params) : "",
                        durationMs, false, e.getMessage());
            }
            return ToolResult.error(e.getMessage());
        }
    }

    @Override
    public ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission,
                               String schemaId, String nodeId, String schemaTargetPath) {
        long startTime = System.currentTimeMillis();
        if (toolId == null) {
            return ToolResult.error("Tool ID is null — malformed tool call from agent");
        }
        // Normalize schemaTargetPath to absolute path (not relative to JVM CWD)
        if (schemaTargetPath != null && !schemaTargetPath.isBlank() && !schemaTargetPath.startsWith("/")) {
            schemaTargetPath = appConfig.getBasePath() + "/" + schemaTargetPath.replaceAll("^/*", "");
        }
        if ("build_app".equals(toolId)) {
            return buildToolHandler.handleBuildApp(params, permission, schemaTargetPath);
        }
        if ("ask_planner".equals(toolId)) {
            return handlerService.handleAskPlanner(params, permission, schemaId, nodeId);
        }
        if ("file_read".equals(toolId)) {
            return handlerService.handleFileReadWithSandbox(params, permission, schemaTargetPath);
        }
        if ("file_write".equals(toolId) || "write_file".equals(toolId) || "create_file".equals(toolId) || "save_file".equals(toolId)) {
            return handlerService.handleFileWriteWithSandbox(params, permission, schemaTargetPath, schemaId, nodeId);
        }
        if ("directory_read".equals(toolId) || "list_dir".equals(toolId) || "ls".equals(toolId)) {
            return handlerService.handleDirectoryReadWithSandbox(params, permission, schemaTargetPath);
        }

        // Resolve relative paths against schemaTargetPath resolved to project root
        // (not JVM CWD which is backend/ during Maven execution).
        if (params != null && schemaTargetPath != null && !schemaTargetPath.isBlank()) {
            // Resolve schemaTargetPath to absolute path against project basePath
            String absTarget = schemaTargetPath.startsWith("/") ? schemaTargetPath
                    : appConfig.getBasePath() + "/" + schemaTargetPath.replaceAll("^/*", "");
            String path = (String) params.get("path");
            if (path != null && !path.startsWith("/")) {
                // Strip leading "./" that models often emit
                String cleanPath = path.startsWith("./") ? path.substring(2) : path;
                // Strip target dir prefix if model already included it
                String targetDir = schemaTargetPath.contains("/")
                        ? schemaTargetPath.substring(schemaTargetPath.lastIndexOf('/') + 1)
                        : schemaTargetPath;
                if (cleanPath.startsWith(targetDir + "/")) {
                    cleanPath = cleanPath.substring(targetDir.length() + 1);
                }
                params.put("path", absTarget.replaceAll("/+$", "") + "/" + cleanPath);
            }
            // Also ensure bash commands run in the target directory
            if ("bash".equals(toolId) && !params.containsKey("cwd")) {
                params.put("cwd", absTarget.replaceAll("/+$", ""));
            }
        }

        ToolExecutorHandler handler = handlers.get(toolId);
        if (handler != null) {
            ToolResult result = handler.execute(params, permission);
            long durationMs = System.currentTimeMillis() - startTime;
            if (schemaId != null && nodeId != null && webSocketHandler != null) {
                webSocketHandler.sendToolCall(schemaId, nodeId, toolId, params != null ? sanitizeParams(params) : "",
                        durationMs, result.isSuccess(), result.getOutput());
            }
            return result;
        }
        return ToolResult.error("No handler for tool: " + toolId);
    }

    @Override
    public ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission,
                               String schemaId, String nodeId, String schemaTargetPath, String projectTypeStr) {
        if (projectTypeStr != null && !projectTypeStr.isBlank()) {
            try {
                handlerService.setProjectType(ProjectType.valueOf(projectTypeStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                // Unknown project type, use default
            }
        }
        return execute(toolId, params, permission, schemaId, nodeId, schemaTargetPath);
    }

    // ── Sandbox-aware public wrappers (used by AgentNodeStrategy) ──

    @Override
    public ToolResult handleFileReadWithSandbox(Map<String, Object> params, ToolPermission permission, String schemaTargetPath) {
        return handlerService.handleFileReadWithSandbox(params, permission, schemaTargetPath);
    }

    /**
     * Sanitize tool parameters for WebSocket logging: mask known secret keys.
     */
    private String sanitizeParams(Map<String, Object> params) {
        Set<String> secretKeys = Set.of("apiKey", "api_key", "token", "secret", "password", "authorization");
        Map<String, Object> safe = new HashMap<>(params);
        for (String key : safe.keySet()) {
            if (secretKeys.contains(key)) {
                safe.put(key, "***");
            }
        }
        return safe.toString();
    }

    @Override
    public ToolResult handleFileWriteWithSandbox(Map<String, Object> params, ToolPermission permission,
                                                   String schemaTargetPath, String schemaId, String nodeId) {
        return handlerService.handleFileWriteWithSandbox(params, permission, schemaTargetPath, schemaId, nodeId);
    }

    @Override
    public ToolResult handleDirectoryReadWithSandbox(Map<String, Object> params, ToolPermission permission, String schemaTargetPath) {
        return handlerService.handleDirectoryReadWithSandbox(params, permission, schemaTargetPath);
    }
}
