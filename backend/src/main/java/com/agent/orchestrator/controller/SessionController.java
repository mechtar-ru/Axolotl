package com.agent.orchestrator.controller;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.Plan;
import com.agent.orchestrator.model.Task;
import com.agent.orchestrator.model.TaskStatus;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.service.PlanService;
import com.agent.orchestrator.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Request body for POST /schemas/{id}/session/plan
 */
record SessionPlanRequest(String message, java.util.List<ChatMessage> history) {}
record ChatMessage(String role, String content) {}
record SessionPlanResponse(String reply) {}

@RestController
@RequestMapping("/api")
@CrossOrigin
public class SessionController {
    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final Neo4jSchemaRepository neo4jSchemaRepository;
    private final PlanService planService;
    private final ExecutionRepository executionRepository;
    private final SettingsService settingsService;
    private final LlmService llmService;

    public SessionController(Neo4jSchemaRepository neo4jSchemaRepository,
                             PlanService planService,
                             ExecutionRepository executionRepository,
                             SettingsService settingsService,
                             LlmService llmService) {
        this.neo4jSchemaRepository = neo4jSchemaRepository;
        this.planService = planService;
        this.executionRepository = executionRepository;
        this.settingsService = settingsService;
        this.llmService = llmService;
    }

    @PostMapping("/schemas/{id}/session/plan")
    public SessionPlanResponse sessionPlan(
            @PathVariable String id,
            @RequestBody SessionPlanRequest body) {
        WorkflowSchema schema = neo4jSchemaRepository.findById(id);
        if (schema == null) {
            throw new RuntimeException("Schema not found: " + id);
        }

        StringBuilder context = new StringBuilder();
        context.append("## Project: ").append(schema.getName()).append("\n\n");
        if (schema.getDescription() != null && !schema.getDescription().isBlank()) {
            context.append("### Description\n").append(schema.getDescription()).append("\n\n");
        }

        // Plan tasks
        try {
            Plan plan = planService.getPlanBySchemaId(id);
            if (plan != null && plan.getTasks() != null && !plan.getTasks().isEmpty()) {
                context.append("### Plan Tasks\n");
                for (Task task : plan.getTasks()) {
                    context.append("- [").append(task.getStatus() == TaskStatus.DONE ? "x" : " ")
                            .append("] ").append(task.getTitle());
                    if (task.getGeneratedFiles() != null && !task.getGeneratedFiles().isEmpty()) {
                        context.append(" (").append(task.getGeneratedFiles().size()).append(" files)");
                    }
                    context.append("\n");
                }
                context.append("\n");
            }
        } catch (Exception e) {
            log.warn("Failed to load plan for schema {}: {}", id, e.getMessage());
        }

        // Previous executions
        try {
            List<ExecutionRun> runs = executionRepository.getRunsBySchema(id);
            if (runs != null && !runs.isEmpty()) {
                context.append("### Previous Sessions\n");
                int sessionNum = 1;
                for (ExecutionRun run : runs) {
                    context.append("- Session ").append(sessionNum++).append(": ").append(run.getStatus());
                    if (run.getGeneratedFiles() != null && !run.getGeneratedFiles().isEmpty()) {
                        context.append(" (").append(run.getGeneratedFiles().size()).append(" files)");
                    }
                    context.append("\n");
                    if (run.getGeneratedFiles() != null) {
                        for (String f : run.getGeneratedFiles()) {
                            if (f != null) context.append("  - ").append(f).append("\n");
                        }
                    }
                }
                context.append("\n");
            }
        } catch (Exception e) {
            log.warn("Failed to load runs for schema {}: {}", id, e.getMessage());
        }

        // Target path files
        try {
            String targetPath = schema.getTargetPath();
            if (targetPath != null && !targetPath.isBlank()) {
                java.nio.file.Path path = java.nio.file.Paths.get(targetPath);
                if (java.nio.file.Files.exists(path)) {
                    context.append("### Generated Files\n");
                    try (var files = java.nio.file.Files.walk(path)
                            .filter(java.nio.file.Files::isRegularFile)
                            .filter(p -> !p.toString().contains("/.dart_tool/")
                                    && !p.toString().contains("/build/")
                                    && !p.toString().contains("/android/")
                                    && !p.toString().contains("/ios/")
                                    && !p.toString().contains("/macos/")
                                    && !p.toString().contains("/linux/")
                                    && !p.toString().contains("/windows/")
                                    && !p.toString().contains("/web/")
                                    && !p.toString().contains("pubspec.lock")
                                    && !p.toString().contains(".flutter-plugins"))) {
                        List<java.nio.file.Path> list = files.toList();
                        if (!list.isEmpty()) {
                            java.nio.file.Path root = path;
                            for (java.nio.file.Path f : list) {
                                context.append("- ").append(root.relativize(f)).append("\n");
                            }
                        } else {
                            context.append("(no source files yet)\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan files for schema {}: {}", id, e.getMessage());
        }

        context.append("\n### Your Role\n")
                .append("You are a session planner for an AI app generation platform. ")
                .append("Your job is to discuss with the user what features to implement next. ")
                .append("Based on the project state above, suggest concrete next steps. ")
                .append("Be concise and actionable. When the user decides what to build, ")
                .append("summarize the plan in 2-3 sentences that will guide the AI agent.");

        // Build conversation messages
        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append(context).append("\n\n");

        if (body.history() != null) {
            for (ChatMessage msg : body.history()) {
                fullPrompt.append(msg.role()).append(": ").append(msg.content()).append("\n");
            }
        }
        fullPrompt.append("user: ").append(body.message()).append("\n");
        fullPrompt.append("assistant: ");

        // Call LLM
        String model = schema.getDefaultModel();
        if (model == null || model.isBlank()) {
            try {
                model = settingsService.getGlobalDefaultModel();
            } catch (Exception e) {
                log.warn("Failed to get system model: {}", e.getMessage());
            }
        }

        var config = new java.util.HashMap<String, Object>();
        config.put("max_tokens", 1024);
        config.put("temperature", 0.7);

        log.info("Session planner chat for schema {} using model {}", id, model != null ? model : "default");
        LlmResponse response = llmService.chat(model != null ? model : "", "", fullPrompt.toString(), config);

        String reply = response != null && response.text() != null && !response.text().isBlank()
                ? response.text()
                : "I can see the project state. What would you like to build next?";

        return new SessionPlanResponse(reply);
    }
}
