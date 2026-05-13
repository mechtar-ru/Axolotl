package com.agent.orchestrator.controller;

import com.agent.orchestrator.config.AppConfig;
import com.agent.orchestrator.model.AppModel;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.service.SchemaService;
import com.agent.orchestrator.service.PlanService;
import com.agent.orchestrator.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/app")
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);
    private final SchemaService schemaService;
    private final PlanService planService;
    private final AppConfig appConfig;

    public AppController(SchemaService schemaService, PlanService planService, AppConfig appConfig) {
        this.schemaService = schemaService;
        this.planService = planService;
        this.appConfig = appConfig;
    }

    // GET /api/app — list all apps
    @GetMapping
    public ResponseEntity<List<AppModel>> getAllApps() {
        List<WorkflowSchema> schemas = schemaService.getAllSchemas();
        List<AppModel> apps = schemas.stream()
                .map(AppModel::fromSchema)
                .toList();
        return ResponseEntity.ok(apps);
    }

    // POST /api/app — create app
    @PostMapping
    public ResponseEntity<AppModel> createApp(@RequestBody CreateAppRequest req) {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName(req.name());
        schema.setDescription(req.description());
        if (req.appType() != null) {
            schema.setAppType(req.appType());
        }
        schema.setWorkspaceId(req.workspaceId());

        // Compute target path
        String targetPath;
        if (req.customTargetPath() != null && !req.customTargetPath().isBlank()) {
            targetPath = req.customTargetPath();
        } else {
            targetPath = appConfig.targetPathFor(req.name());
        }
        schema.setTargetPath(targetPath);

        // Handle conflict action
        String conflictAction = req.conflictAction();
        if ("OVERWRITE".equalsIgnoreCase(conflictAction)) {
            Path path = Path.of(targetPath);
            try {
                if (Files.exists(path)) {
                    Files.walk(path)
                            .sorted(Comparator.reverseOrder())
                            .map(java.nio.file.Path::toFile)
                            .forEach(java.io.File::delete);
                }
                Files.createDirectories(path);
                schema.setTargetPathConflictAction("OVERWRITE");
            } catch (IOException e) {
                log.warn("Failed to handle OVERWRITE for path {}: {}", targetPath, e.getMessage());
            }
        } else if ("CHANGE_PATH".equalsIgnoreCase(conflictAction)) {
            if (req.customTargetPath() != null && !req.customTargetPath().isBlank()) {
                schema.setTargetPath(req.customTargetPath());
                schema.setTargetPathConflictAction("CHANGE_PATH");
            }
        } else {
            // CONTINUE or null — leave as-is
            schema.setTargetPathConflictAction("CONTINUE");
        }

        WorkflowSchema created = schemaService.createSchema(schema);
        return ResponseEntity.ok(AppModel.fromSchema(created));
    }

    // GET /api/app/{id} — get app by id
    @GetMapping("/{id}")
    public ResponseEntity<AppModel> getApp(@PathVariable String id) {
        WorkflowSchema schema = schemaService.getSchema(id);
        if (schema == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(AppModel.fromSchema(schema));
    }

    // PUT /api/app/{id} — update app
    @PutMapping("/{id}")
    public ResponseEntity<AppModel> updateApp(@PathVariable String id, @RequestBody AppModel appModel) {
        WorkflowSchema existing = schemaService.getSchema(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        existing.setName(appModel.getName());
        existing.setDescription(appModel.getDescription());
        if (appModel.getAppType() != null) {
            existing.setAppType(appModel.getAppType().name());
        }
        if (appModel.getWorkspaceId() != null) {
            existing.setWorkspaceId(appModel.getWorkspaceId());
        }

        WorkflowSchema updated = schemaService.updateSchema(id, existing);
        return ResponseEntity.ok(AppModel.fromSchema(updated));
    }

    // DELETE /api/app/{id} — delete app
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteApp(@PathVariable String id) {
        schemaService.deleteSchema(id);
        return ResponseEntity.noContent().build();
    }

    // POST /api/app/check-path — check if target directory already exists for a given app config
    @PostMapping("/check-path")
    public ResponseEntity<Map<String, Object>> checkPath(@RequestBody AppModel appModel) {
        String targetPath = null;
        boolean exists = false;

        if (appModel.getAppType() != null && appModel.getAppType() != AppModel.AppType.CUSTOM) {
            targetPath = appConfig.targetPathFor(appModel.getName());
            exists = Files.exists(Paths.get(targetPath));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("exists", exists);
        response.put("targetPath", targetPath);
        response.put("suggestedActions", List.of("CONTINUE", "OVERWRITE", "CHANGE_PATH"));
        log.info("check-path for app '{}' type={}: exists={}, targetPath={}",
                appModel.getName(), appModel.getAppType(), exists, targetPath);
        return ResponseEntity.ok(response);
    }

    // POST /api/app/resolve-path — create app with conflict resolution (CONTINUE/OVERWRITE/CHANGE_PATH)
    @PostMapping("/resolve-path")
    public ResponseEntity<?> resolvePath(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        String workspaceId = (String) body.get("workspaceId");
        String appTypeStr = (String) body.get("appType");
        String conflictAction = (String) body.get("conflictAction");
        if (conflictAction == null || conflictAction.isEmpty()) {
            conflictAction = "CONTINUE";
        }

        // Parse appType from string
        AppModel.AppType appType = null;
        if (appTypeStr != null && !appTypeStr.isEmpty()) {
            try {
                appType = AppModel.AppType.valueOf(appTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown appType '{}', falling back to CUSTOM", appTypeStr);
                appType = AppModel.AppType.CUSTOM;
            }
        }

        // Build schema
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName(name);
        schema.setDescription(description);
        if (appType != null) {
            schema.setAppType(appType.name());
        }
        schema.setWorkspaceId(workspaceId);

        // Set targetPath for non-CUSTOM app types
        if (appType != null && appType != AppModel.AppType.CUSTOM) {
            String targetPath = appConfig.targetPathFor(schema.getName());
            schema.setTargetPath(targetPath);
            schema.setTargetPathConflictAction(conflictAction);

            // Handle directory based on conflict action
            try {
                switch (conflictAction) {
                    case "OVERWRITE":
                        if (Files.exists(Paths.get(targetPath))) {
                            deleteDirectory(targetPath);
                        }
                        Files.createDirectories(Paths.get(targetPath));
                        log.info("OVERWRITE: cleared and created directory: {}", targetPath);
                        break;
                    case "CONTINUE":
                        Files.createDirectories(Paths.get(targetPath));
                        log.info("CONTINUE: ensured directory exists: {}", targetPath);
                        break;
                    case "CHANGE_PATH":
                        // CHANGE_PATH: path is set on schema but directory is not modified
                        log.info("CHANGE_PATH: targetPath set on schema, directory untouched: {}", targetPath);
                        break;
                    default:
                        log.warn("Unknown conflictAction '{}', treating as CONTINUE", conflictAction);
                        Files.createDirectories(Paths.get(targetPath));
                        break;
                }
            } catch (IOException e) {
                log.error("Failed to handle target directory: {}", targetPath, e);
                return ResponseEntity.status(500).body(Map.of("error", "Failed to handle target directory: " + e.getMessage()));
            }
        }

        WorkflowSchema created = schemaService.createSchema(schema);
        log.info("resolve-path created schema '{}' (ID: {}) with conflictAction={}", created.getName(), created.getId(), conflictAction);
        return ResponseEntity.ok(AppModel.fromSchema(created));
    }

    // GET /api/app/check-target-path — check if target path exists
    @GetMapping("/check-target-path")
    public ResponseEntity<Map<String, Object>> checkTargetPath(
            @RequestParam String name,
            @RequestParam String appType) {
        Map<String, Object> result = new LinkedHashMap<>();

        if ("CUSTOM".equalsIgnoreCase(appType)) {
            result.put("exists", false);
            result.put("targetPath", null);
            return ResponseEntity.ok(result);
        }

        String targetPath = appConfig.targetPathFor(name);
        boolean exists = Files.exists(Path.of(targetPath));
        result.put("exists", exists);
        result.put("targetPath", targetPath);
        return ResponseEntity.ok(result);
    }

    // GET /api/app/{id}/generated-files — get generated files for app
    @GetMapping("/{id}/generated-files")
    public ResponseEntity<List<Task.GeneratedFile>> getGeneratedFiles(@PathVariable String id) {
        WorkflowSchema schema = schemaService.getSchema(id);
        if (schema == null) {
            return ResponseEntity.notFound().build();
        }

        com.agent.orchestrator.model.Plan plan = planService.getPlanBySchemaId(id);
        if (plan == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        // Find DONE task with matching schemaId
        for (Task task : plan.getTasks()) {
            if (task.getStatus() == com.agent.orchestrator.model.TaskStatus.DONE
                    && id.equals(task.getSchemaId())) {
                List<Task.GeneratedFile> files = task.getGeneratedFiles();
                return ResponseEntity.ok(files != null ? files : Collections.emptyList());
            }
        }

        return ResponseEntity.ok(Collections.emptyList());
    }

    // GET /api/app/templates — return hardcoded template list
    @GetMapping("/templates")
    public ResponseEntity<List<Map<String, Object>>> getTemplates() {
        List<Map<String, Object>> templates = new ArrayList<>();

        // Template 1: Chat Bot
        Map<String, Object> chatBot = new LinkedHashMap<>();
        chatBot.put("id", "template-chat");
        chatBot.put("name", "Chat Bot");
        chatBot.put("description", "AI chatbot with conversation memory");
        chatBot.put("appType", "CHAT");
        chatBot.put("nodes", List.of());
        chatBot.put("edges", List.of());
        templates.add(chatBot);

        // Template 2: Document Analyzer
        Map<String, Object> docAnalyzer = new LinkedHashMap<>();
        docAnalyzer.put("id", "template-doc");
        docAnalyzer.put("name", "Document Analyzer");
        docAnalyzer.put("description", "Analyze documents with AI");
        docAnalyzer.put("appType", "ANALYZER");
        docAnalyzer.put("nodes", List.of());
        docAnalyzer.put("edges", List.of());
        templates.add(docAnalyzer);

        // Template 3: Content Generator
        Map<String, Object> contentGen = new LinkedHashMap<>();
        contentGen.put("id", "template-content");
        contentGen.put("name", "Content Generator");
        contentGen.put("description", "Generate articles, posts, and more");
        contentGen.put("appType", "GENERATOR");
        contentGen.put("nodes", List.of());
        contentGen.put("edges", List.of());
        templates.add(contentGen);

        // Template 4: Email Agent
        Map<String, Object> emailAgent = new LinkedHashMap<>();
        emailAgent.put("id", "template-email");
        emailAgent.put("name", "Email Agent");
        emailAgent.put("description", "Smart email assistant");
        emailAgent.put("appType", "EMAIL");
        emailAgent.put("nodes", List.of());
        emailAgent.put("edges", List.of());
        templates.add(emailAgent);

        // Template 5: Sokoban Game
        Map<String, Object> sokoban = new LinkedHashMap<>();
        sokoban.put("id", "template-sokoban");
        sokoban.put("name", "Sokoban Game");
        sokoban.put("description", "Generate a playable Sokoban puzzle game");
        sokoban.put("appType", "GAME");
        sokoban.put("nodes", List.of());
        sokoban.put("edges", List.of());
        templates.add(sokoban);

        // Template 6: Data Extractor
        Map<String, Object> dataExtractor = new LinkedHashMap<>();
        dataExtractor.put("id", "template-data");
        dataExtractor.put("name", "Data Extractor");
        dataExtractor.put("description", "Extract structured data from text");
        dataExtractor.put("appType", "ANALYZER");
        dataExtractor.put("nodes", List.of());
        dataExtractor.put("edges", List.of());
        templates.add(dataExtractor);

        // Template 7: Blank
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("id", "template-blank");
        blank.put("name", "Blank App");
        blank.put("description", "Start from scratch");
        blank.put("appType", "CUSTOM");
        blank.put("nodes", List.of());
        blank.put("edges", List.of());
        templates.add(blank);

        return ResponseEntity.ok(templates);
    }

    // --- Inner classes ---

    public record CreateAppRequest(
            String name,
            String description,
            String appType,
            String workspaceId,
            String conflictAction,
            String customTargetPath) {}

    // --- Private helpers ---

    private void deleteDirectory(String path) throws IOException {
        java.nio.file.Path dir = Paths.get(path);
        if (Files.exists(dir)) {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("Failed to delete: {}", p, e);
                    }
                });
        }
    }
}
