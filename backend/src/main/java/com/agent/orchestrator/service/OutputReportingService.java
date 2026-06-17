package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service responsible for output node execution and reporting.
 * Extracted from ExecutionUtilityService to separate output/reporting concerns.
 */
@Service
public class OutputReportingService {

    private static final Logger log = LoggerFactory.getLogger(OutputReportingService.class);

    private final ExecutionWebSocketHandler webSocketHandler;
    private final MemPalaceClient memPalaceClient;
    private final Neo4jSchemaRepository schemaRepository;
    private final ExecutionStateManager stateManager;
    private final NodeFileWriter nodeFileWriter;
    private final ExecutionUtilityService utilityService;

    public OutputReportingService(ExecutionWebSocketHandler webSocketHandler,
                                  MemPalaceClient memPalaceClient,
                                  Neo4jSchemaRepository schemaRepository,
                                  ExecutionStateManager stateManager,
                                  NodeFileWriter nodeFileWriter,
                                  ExecutionUtilityService utilityService) {
        this.webSocketHandler = webSocketHandler;
        this.memPalaceClient = memPalaceClient;
        this.schemaRepository = schemaRepository;
        this.stateManager = stateManager;
        this.nodeFileWriter = nodeFileWriter;
        this.utilityService = utilityService;
    }

    // ────────────────────────── write output ──────────────────────────

    public String writeOutput(String outputType, String filePath, String fileFormat, String content) {
        return nodeFileWriter.writeOutput(outputType, filePath, fileFormat, content);
    }

    // ────────────────────────── path sandbox ──────────────────────────

    public boolean isPathAllowed(String filePath) {
        return nodeFileWriter.isPathAllowed(filePath);
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

        var predResults = utilityService.collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
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
        // Resolve relative file paths against schema targetPath
        if (filePath != null && !filePath.isBlank() && !filePath.startsWith("/")) {
            String targetPath = null;
            try {
                var schema = schemaRepository.findById(schemaId);
                if (schema != null) {
                    targetPath = schema.getTargetPath();
                }
            } catch (Exception e) {
                log.warn("Could not resolve targetPath for schema {}: {}", schemaId, e.getMessage());
            }
            if (targetPath != null && !targetPath.isBlank()) {
                filePath = targetPath.endsWith("/") ? targetPath + filePath : targetPath + "/" + filePath;
            }
        }
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
