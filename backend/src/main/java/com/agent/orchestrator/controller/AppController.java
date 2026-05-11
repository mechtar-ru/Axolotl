package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.AppModel;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.service.SchemaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/app")
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);
    private final SchemaService schemaService;

    public AppController(SchemaService schemaService) {
        this.schemaService = schemaService;
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
    public ResponseEntity<AppModel> createApp(@RequestBody AppModel appModel) {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setName(appModel.getName());
        schema.setDescription(appModel.getDescription());
        if (appModel.getAppType() != null) {
            schema.setAppType(appModel.getAppType().name());
        }
        schema.setWorkspaceId(appModel.getWorkspaceId());

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
}
