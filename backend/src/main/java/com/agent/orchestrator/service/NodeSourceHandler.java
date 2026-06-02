package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NodeSourceHandler {

    private static final Logger log = LoggerFactory.getLogger(NodeSourceHandler.class);

    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final Neo4jSchemaRepository schemaRepository;
    private final ProjectContextBuilder projectContextBuilder;
    private final ExecutionStateManager stateManager;

    public NodeSourceHandler(ExecutionWebSocketHandler webSocketHandler,
                             MemPalaceClient memPalaceClient,
                             Neo4jSchemaRepository schemaRepository,
                             ProjectContextBuilder projectContextBuilder,
                             ExecutionStateManager stateManager) {
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.schemaRepository = schemaRepository;
        this.projectContextBuilder = projectContextBuilder;
        this.stateManager = stateManager;
    }

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
            String sourceData = node.getData() != null ? node.getData().getSourceData() : null;
            if (sourceData == null || sourceData.isEmpty()) {
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
                long maxSize = 1024 * 1024;
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
            if (node.getData() != null && node.getData().getSourceData() != null
                    && !node.getData().getSourceData().isEmpty()) {
                return node.getData().getSourceData();
            } else {
                return "Данные из источника: " + node.getName();
            }
        }
    }

    public String resolveSourceData(WorkflowSchema schema) {
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
}
