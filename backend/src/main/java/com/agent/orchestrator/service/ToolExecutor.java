package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolCategory;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

@Service
public class ToolExecutor {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolExecutorHandler> handlers = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private ExecutionWebSocketHandler webSocketHandler;

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

        handlers.put("file_read", this::handleFileRead);
        handlers.put("file_write", this::handleFileWrite);
        handlers.put("directory_read", this::handleDirectoryRead);
        handlers.put("bash", this::handleBash);
        handlers.put("memory_read", this::handleMemoryRead);
        handlers.put("memory_write", this::handleMemoryWrite);
        handlers.put("web_search", this::handleWebSearch);
        handlers.put("web_fetch", this::handleWebFetch);
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

    @FunctionalInterface
    public interface ToolExecutorHandler {
        ToolResult execute(Map<String, Object> params, ToolPermission permission);
    }
}