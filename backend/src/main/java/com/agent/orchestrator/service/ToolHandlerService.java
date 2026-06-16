package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.ProjectType;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All tool handler implementations extracted from ToolExecutorImpl.
 * Keeps handler logic separate from the registration/dispatch infrastructure.
 */
@Service
public class ToolHandlerService {

    private static final Logger log = LoggerFactory.getLogger(ToolHandlerService.class);

    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final ExecutionStateManager stateManager;
    private final Driver neo4jDriver;
    private final Neo4jSchemaRepository schemaRepository;

    private static final String DELETION_MARKER_FILE = "/Users/Shared/Axolotl/deleted_files.json";

    private ProjectType projectType = ProjectType.FLUTTER;

    public void setProjectType(ProjectType pt) { this.projectType = pt; }

    /** Configured allowed commands from application.yml, falls back to DEFAULT_ALLOWED_COMMANDS */
    @Value("${axolotl.tools.allowed-commands:}")
    private String allowedCommandsConfig;

    private Set<String> getAllowedCommands() {
        if (allowedCommandsConfig != null && !allowedCommandsConfig.isBlank()) {
            return Set.of(allowedCommandsConfig.trim().split("\\s*,\\s*"));
        }
        return ToolExecutorImpl.DEFAULT_ALLOWED_COMMANDS;
    }

    public ToolHandlerService(LlmService llmService,
                              ExecutionWebSocketHandler webSocketHandler,
                              ExecutionStateManager stateManager,
                              Driver neo4jDriver,
                              Neo4jSchemaRepository schemaRepository) {
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.stateManager = stateManager;
        this.neo4jDriver = neo4jDriver;
        this.schemaRepository = schemaRepository;
    }

    // ── File Read ──

    public ToolResult handleFileRead(Map<String, Object> params, ToolPermission permission) {
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

    // ── File Write ──

    public ToolResult handleFileWrite(Map<String, Object> params, ToolPermission permission) {
        String path = (String) params.get("path");
        if (path == null) path = (String) params.get("file_path");
        if (path == null) path = (String) params.get("filePath");
        String content = (String) params.get("content");
        if (content == null) content = (String) params.get("code");
        if (content == null) content = (String) params.get("body");
        if (content == null) content = (String) params.get("data");
        if (path == null || content == null) return ToolResult.error("Missing path or content");

        try {
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

    // ── Directory Read ──

    public ToolResult handleDirectoryRead(Map<String, Object> params, ToolPermission permission) {
        String path = (String) params.get("path");
        if (path == null) path = ".";

        try (Stream<Path> stream = Files.list(Path.of(path))) {
            List<String> files = stream.map(p -> p.toString()).sorted().collect(Collectors.toList());
            return ToolResult.ok(String.join("\n", files));
        } catch (IOException e) {
            return ToolResult.error("Failed to list directory: " + e.getMessage());
        }
    }

    // ── Bash ──

    public ToolResult handleBash(Map<String, Object> params, ToolPermission permission) {
        String command = (String) params.get("command");
        String cwd = (String) params.get("cwd");
        Integer timeout = params.get("timeout") != null ? (Integer) params.get("timeout") : 30;

        if (command == null) return ToolResult.error("Missing command parameter");

        if (permission != null && !permission.allowsCommand(command)) {
            return ToolResult.error("Command blocked by permissions: " + command);
        }

        String cmdName = command.trim().split("\\s+")[0];
        if (!getAllowedCommands().contains(cmdName)) {
            return ToolResult.error("Command not allowed: " + cmdName + ". Allowed commands: " + getAllowedCommands());
        }

        if (command.trim().startsWith("rm ") || command.trim().equals("rm")) {
            return interceptRmCommand(command, cwd);
        }

        if (command.contains("$(") || command.contains("`")) {
            return ToolResult.error("Command blocked: substitution ($(..) or backticks) not allowed");
        }

        if (command.contains("|")) {
            String[] pipeline = command.split("\\|");
            for (String segment : pipeline) {
                segment = segment.trim();
                if (segment.isEmpty()) continue;
                String segCmd = segment.split("\\s+")[0];
                if (!ToolExecutorImpl.DEFAULT_ALLOWED_COMMANDS.contains(segCmd)) {
                    return ToolResult.error("Pipe chain blocked: '" + segCmd + "' not in allowed set: " + ToolExecutorImpl.DEFAULT_ALLOWED_COMMANDS);
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

    // ── Memory Read ──

    public ToolResult handleMemoryRead(Map<String, Object> params, ToolPermission permission) {
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

    // ── Memory Write ──

    public ToolResult handleMemoryWrite(Map<String, Object> params, ToolPermission permission) {
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

    // ── Web Search ──

    public ToolResult handleWebSearch(Map<String, Object> params, ToolPermission permission) {
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

    // ── Web Fetch ──

    public ToolResult handleWebFetch(Map<String, Object> params, ToolPermission permission) {
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

    // ── RLM Predict ──

    public ToolResult handleRlmPredict(Map<String, Object> params, ToolPermission permission) {
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

    // ── Grep ──

    public ToolResult handleGrep(Map<String, Object> params, ToolPermission permission) {
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

    // ── Git ──

    public ToolResult handleGit(Map<String, Object> params, ToolPermission permission) {
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

    // ── Memory Search ──

    public ToolResult handleMemorySearch(Map<String, Object> params, ToolPermission permission) {
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

    // ── Web API ──

    public ToolResult handleWebApi(Map<String, Object> params, ToolPermission permission) {
        String url = (String) params.get("url");
        String method = params.get("method") != null ? (String) params.get("method") : "GET";
        if (url == null) {
            return ToolResult.error("Missing required parameter: url");
        }
        return ToolResult.ok("[Web API call: " + method + " " + url + " - implement with HTTP client]");
    }

    // ── Graph Query ──

    public ToolResult handleGraphQuery(Map<String, Object> params, ToolPermission permission) {
        String query = (String) params.get("query");
        String type = params.get("type") != null ? (String) params.get("type") : "search";

        if (query == null) {
            return ToolResult.error("Missing required parameter: query");
        }

        if (neo4jDriver == null) {
            return ToolResult.error("Neo4j driver not configured");
        }

        try (var session = neo4jDriver.session()) {
            var records = session.executeRead(tx -> tx.run(query).list());
            List<String> rows = new ArrayList<>();
            for (var record : records) {
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

    // ── MCP Execute ──

    public ToolResult handleMcpExecute(Map<String, Object> params, ToolPermission permission) {
        String server = (String) params.get("server");
        String tool = (String) params.get("tool");
        Object args = params.get("args");

        if (server == null || tool == null) {
            return ToolResult.error("Missing required parameters: server, tool");
        }

        return ToolResult.ok("[MCP execute: " + server + ":" + tool + " - implement with MCP client]");
    }

    // ── Sandbox ──

    public String validateSandboxPath(String requestedPath, ToolPermission permission, String schemaTargetPath) {
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

    public ToolResult handleFileWriteWithSandbox(Map<String, Object> params, ToolPermission permission,
                                                   String schemaTargetPath, String schemaId, String nodeId) {
        String path = (String) params.get("path");
        String content = (String) params.get("content");
        if (path == null || content == null) return ToolResult.error("Missing path or content");

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
            String verifyStatus = "";
            try {
                String written = java.nio.file.Files.readString(targetPath);
                String writtenHash = contentHash(written);
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

    public ToolResult handleDirectoryReadWithSandbox(Map<String, Object> params, ToolPermission permission, String schemaTargetPath) {
        String path = (String) params.get("path");
        if (path == null) path = schemaTargetPath;

        try (Stream<Path> stream = Files.list(Path.of(path != null ? path : "."))) {
            List<String> files = stream.map(p -> p.toString()).sorted().collect(Collectors.toList());
            return ToolResult.ok(String.join("\n", files));
        } catch (IOException e) {
            return ToolResult.error("Failed to list directory: " + e.getMessage());
        }
    }

    public String runPostWriteValidator(String filePath) {
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

    static String contentHash(String content) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (Exception e) {
            return "????";
        }
    }

    static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot) : "";
    }

    // ── Ask Planner ──

    /**
     * Calls the planner model (OpenRouter/thinker) with a prompt and returns text response.
     * Used by the executor model when it needs architectural guidance, code generation, or gap analysis.
     * Not registered in the handler map — called directly from ToolExecutorImpl with schemaId/nodeId.
     */
    public ToolResult handleAskPlanner(Map<String, Object> params, ToolPermission permission,
                                        String schemaId, String nodeId) {
        String prompt = (String) params.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ToolResult.error("'prompt' argument is required");
        }

        // Look up planner model from node data
        String plannerModel = null;
        try {
            WorkflowSchema schema = schemaRepository.findById(schemaId);
            if (schema != null && schema.getNodes() != null) {
                for (Node n : schema.getNodes()) {
                    if (n.getId().equals(nodeId) && n.getData() != null) {
                        plannerModel = n.getData().getPlannerModel();
                        if (plannerModel == null || plannerModel.isBlank()) {
                            plannerModel = n.getData().getModel();
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to look up planner model: {}", e.getMessage());
        }

        if (plannerModel == null || plannerModel.isBlank()) {
            // Fallback: use node's model directly
            try {
                WorkflowSchema schema = schemaRepository.findById(schemaId);
                if (schema != null) {
                    plannerModel = schema.getDefaultModel();
                }
            } catch (Exception e) {
                return ToolResult.error("No planner model configured and cannot determine fallback: " + e.getMessage());
            }
        }

        if (plannerModel == null || plannerModel.isBlank()) {
            return ToolResult.error("No planner model configured on this node");
        }

        // Call planner model (NO tools — pure text generation)
        try {
            LlmResponse resp = llmService.chat(plannerModel,
                "You are a senior software architect and Flutter expert. Provide detailed, complete, production-quality code and architecture guidance.",
                prompt, null, null);
            String text = resp != null ? resp.text() : "";
            if (text == null || text.isBlank()) {
                return ToolResult.error("Planner returned empty response");
            }
            return ToolResult.ok(text);
        } catch (Exception e) {
            return ToolResult.error("Planner call failed: " + e.getMessage());
        }
    }
}
