package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.Agent;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.ExecutionRecord;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.model.Pipeline;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.service.AgentService;
import com.agent.orchestrator.service.PipelineService;
import com.agent.orchestrator.service.PlanningService;
import com.agent.orchestrator.service.SchemaService;
import com.agent.orchestrator.service.SettingsService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final SchemaService schemaService;
    private final PipelineService pipelineService;
    private final LlmService llmService;
    private final MemPalaceClient memPalaceClient;
    private final PlanningService planningService;
    private final SettingsService settingsService;

    public AgentController(AgentService agentService, SchemaService schemaService,
                           PipelineService pipelineService,
                           LlmService llmService, MemPalaceClient memPalaceClient,
                           PlanningService planningService, SettingsService settingsService) {
        this.agentService = agentService;
        this.schemaService = schemaService;
        this.pipelineService = pipelineService;
        this.llmService = llmService;
        this.memPalaceClient = memPalaceClient;
        this.planningService = planningService;
        this.settingsService = settingsService;
    }

    @GetMapping("/agents")
    public List<Agent> getAgents() {
        return agentService.getAllAgents();
    }

    @PostMapping("/agents/{id}/chat")
    public Map<String, String> chat(
            @PathVariable String id,
            @RequestBody Map<String, String> request) {

        String message = request.get("message");
        String sessionKey = request.get("sessionKey");

        String response = agentService.sendMessage(id, message, sessionKey);
        String newSessionKey = agentService.getSessionKey(id, sessionKey);

        Map<String, String> result = new HashMap<>();
        result.put("reply", response);
        result.put("sessionKey", newSessionKey != null ? newSessionKey : "");
        return result;
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return null; // Anonymous — no isolation
    }

    @GetMapping("/schemas")
    public List<WorkflowSchema> getAllSchemas() {
        String userId = getCurrentUserId();
        log.info("getAllSchemas called with userId: {}", userId);
        return schemaService.getSchemasByUserId(userId);
    }

    @GetMapping("/schemas/{id}")
    public WorkflowSchema getSchema(@PathVariable String id) {
        return schemaService.getSchema(id);
    }

    @PostMapping("/schemas")
    public WorkflowSchema createSchema(@RequestBody WorkflowSchema schema) {
        String userId = getCurrentUserId();
        if (userId != null) {
            schema.setUserId(userId);
        }
        return schemaService.createSchema(schema);
    }

    @PutMapping("/schemas/{id}")
    public WorkflowSchema updateSchema(@PathVariable String id, @RequestBody WorkflowSchema schema) {
        return schemaService.updateSchema(id, schema);
    }

    @DeleteMapping("/schemas/{id}")
    public void deleteSchema(@PathVariable String id) {
        schemaService.deleteSchema(id);
    }

    @GetMapping("/schemas/{id}/export/mermaid")
    public Map<String, String> exportToMermaid(@PathVariable String id) {
        String mermaid = schemaService.exportToMermaid(id);
        return Map.of("mermaid", mermaid);
    }

    @GetMapping("/schemas/{id}/export/python")
    public Map<String, String> exportToPython(@PathVariable String id) {
        String python = schemaService.exportToPython(id);
        return Map.of("python", python);
    }

    @PostMapping("/schemas/{id}/execute")
    public Map<String, String> executeSchema(
            @PathVariable String id,
            @RequestParam(defaultValue = "EXECUTE") ExecutionMode mode) {
        schemaService.executeSchema(id);
        return Map.of("status", "started", "schemaId", id, "mode", mode.name());
    }

    @PostMapping("/schemas/{id}/stop")
    public Map<String, String> stopSchema(@PathVariable String id) {
        schemaService.cancelExecution(id);
        return Map.of("status", "stopped", "schemaId", id);
    }

    // ── Execution Runs / Resilience ───────────────────────────

    @GetMapping("/schemas/{id}/runs")
    public List<ExecutionRun> getExecutionRuns(@PathVariable String id) {
        return schemaService.findExecutionRuns(id);
    }

    @GetMapping("/schemas/{id}/runs/paused")
    public ResponseEntity<ExecutionRun> getPausedRun(@PathVariable String id) {
        ExecutionRun run = schemaService.getPausedRun(id);
        return ResponseEntity.ok(run);
    }

    @PostMapping("/schemas/{id}/resume")
    public Map<String, String> resumeExecution(@PathVariable String id) {
        schemaService.resumeExecution(id);
        return Map.of("status", "resumed");
    }

    @GetMapping("/schemas/{id}/runs/{runId}/nodes")
    public List<NodeExecution> getRunNodes(@PathVariable String id, @PathVariable String runId) {
        return schemaService.getExecutionNodes(runId);
    }

    @PostMapping("/execution/{executionId}/feedback")
    public Map<String, String> submitReviewFeedback(
            @PathVariable String executionId,
            @RequestParam String nodeId,
            @RequestBody Map<String, Object> body) {
        String feedback = body.get("feedback") instanceof String ? (String) body.get("feedback") : "";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = body.get("history") instanceof List
                ? (List<Map<String, Object>>) body.get("history") : new ArrayList<>();

        schemaService.handleReviewFeedback(executionId, nodeId, feedback, history);
        log.info("Review feedback received for execution {} node {}: {}", executionId, nodeId,
                feedback.length() > 50 ? feedback.substring(0, 50) + "..." : feedback);

        return Map.of("status", "ok", "message", "Feedback received and review node resumed");
    }

    @PostMapping("/execution/{executionId}/approve-review")
    public Map<String, String> approveReview(
            @PathVariable String executionId,
            @RequestParam String nodeId) {
        schemaService.handleReviewApprove(executionId, nodeId);
        log.info("Review approved for execution {} node {}", executionId, nodeId);
        return Map.of("status", "ok", "message", "Plan approved, resuming execution");
    }

    @PostMapping("/execution/{executionId}/reject")
    public Map<String, String> rejectReview(
            @PathVariable String executionId,
            @RequestParam String nodeId) {
        schemaService.handleReviewReject(executionId, nodeId);
        log.info("Review rejected for execution {} node {}", executionId, nodeId);
        return Map.of("status", "ok", "message", "Plan rejected, node failed");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("message", "Axolotl работает!");
        result.put("ollama", llmService.isProviderAvailable("ollama"));
        return result;
    }

    @PostMapping("/llm/test")
    public Map<String, Object> testLlm(@RequestParam(defaultValue = "ollama") String provider, 
                                        @RequestParam(defaultValue = "gemma4:e2b") String model,
                                        @RequestParam(defaultValue = "Say OK") String message) {
        Map<String, Object> result = new HashMap<>();
        try {
            long start = System.currentTimeMillis();
            String response = llmService.chat(model, null, message, null);
            long duration = System.currentTimeMillis() - start;
            result.put("success", true);
            result.put("response", response);
            result.put("provider", provider);
            result.put("model", model);
            result.put("duration_ms", duration);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ── Tiered Planning ────────────────────────────────────────

    @PostMapping("/schemas/{id}/plan")
    public Map<String, Object> generatePlan(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {

        String prompt = (String) body.get("prompt");
        String level = (String) body.get("level");
        String model = (String) body.get("model");

        if (prompt == null || prompt.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "prompt is required");
        }
        if (level == null || (!"outline".equals(level) && !"refine".equals(level))) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "level must be 'outline' or 'refine'");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("context");

        if ("outline".equals(level)) {
            return planningService.generateOutline(id, prompt, model);
        } else {
            String outline = context != null ? (String) context.get("outline") : null;
            String userEdits = context != null ? (String) context.get("userEdits") : null;
            @SuppressWarnings("unchecked")
            Map<String, String> answers = context != null
                    ? (Map<String, String>) context.get("answers") : null;
            if (answers == null) answers = Map.of();
            return planningService.refinePlan(id, prompt, model, outline, userEdits, answers);
        }
    }

    // === Settings ===

    @GetMapping("/settings/providers")
    public List<Map<String, Object>> getProviders() {
        List<Map<String, Object>> providers = llmService.getProvidersInfo();
        // Enrich built-in providers with disabledModels from persisted settings
        for (Map<String, Object> p : providers) {
            if (Boolean.FALSE.equals(p.get("custom"))) {
                String name = (String) p.get("name");
                p.put("disabledModels", settingsService.getDisabledModels(name));
            }
        }
        return providers;
    }

    @GetMapping("/settings/providers/{name}/models")
    public List<String> getProviderModels(@PathVariable String name) {
        return llmService.listModels(name);
    }

    @GetMapping("/schemas/{id}/history")
    public List<ExecutionRecord> getExecutionHistory(@PathVariable String id) {
        return schemaService.getExecutionHistory(id);
    }

    @GetMapping("/history")
    public List<ExecutionRecord> getAllExecutionHistory() {
        return schemaService.getAllExecutionHistory();
    }

    // === Memory (MemPalace) ===

    @GetMapping("/memory/search")
    public List<Map<String, Object>> searchMemory(
            @RequestParam String query,
            @RequestParam(required = false) String wing,
            @RequestParam(required = false) String room,
            @RequestParam(defaultValue = "5") int limit) {
        return memPalaceClient.search(query, wing, room, limit);
    }

    @GetMapping("/memory/taxonomy")
    public Map<String, Map<String, Integer>> getMemoryTaxonomy() {
        if (!memPalaceClient.isEnabled()) return Map.of();
        // Fetch all drawers and build wing → room → count taxonomy
        return memPalaceClient.getTaxonomy();
    }

    @GetMapping("/memory/drawers")
    public List<Map<String, Object>> listDrawers(
            @RequestParam String wing,
            @RequestParam String room) {
        if (!memPalaceClient.isEnabled()) return List.of();
        return memPalaceClient.listDrawers(wing, room);
    }

    @GetMapping("/memory/tunnels")
    public Map<String, Object> getTunnels(
            @RequestParam String wing_a) {
        if (!memPalaceClient.isEnabled()) return Map.of("tunnels", List.of());
        return memPalaceClient.findTunnels(wing_a);
    }

    @PostMapping("/memory/add")
    public Map<String, Object> addMemoryDrawer(@RequestBody Map<String, String> body) {
        String wing = body.getOrDefault("wing", "axolotl");
        String room = body.getOrDefault("room", "agent-results");
        String content = body.get("content");
        String sourceFile = body.get("source_file");
        boolean ok = memPalaceClient.addDrawer(wing, room, content, sourceFile);
        return Map.of("success", ok, "wing", wing, "room", room);
    }

    @PostMapping("/fetch-url")
    public Map<String, String> fetchUrl(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            return Map.of("status", "error", "error", "URL is required");
        }
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String content = response.body();
                if (content.length() > 50000) content = content.substring(0, 50000);
                return Map.of("status", "ok", "content", content);
            }
            return Map.of("status", "error", "error", "HTTP " + response.statusCode());
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @GetMapping("/outputs/{schemaId}/{nodeId}")
    public Map<String, String> getOutputFile(@PathVariable String schemaId, @PathVariable String nodeId) {
        String content = schemaService.getOutputFileContent(schemaId, nodeId);
        if (content == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return Map.of("content", content);
    }

    // ── Multi-Stage Pipeline ────────────────────────────────────

    @PostMapping("/schemas/{id}/pipeline/build")
    public Map<String, Object> buildPipelineNodes(@PathVariable String id) {
        try {
            WorkflowSchema schema = pipelineService.buildPipelineNodes(id);
            return Map.of("status", "ok", "nodes", schema.getNodes() != null ? schema.getNodes().size() : 0,
                    "edges", schema.getEdges() != null ? schema.getEdges().size() : 0);
        } catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/schemas/{id}/pipeline/execute")
    public Map<String, String> executePipeline(@PathVariable String id) {
        pipelineService.executePipeline(id);
        return Map.of("status", "ok", "message", "Pipeline execution started");
    }

    @PostMapping("/schemas/{id}/pipeline/retry")
    public Map<String, String> retryPipeline(@PathVariable String id) {
        try {
            pipelineService.retryPipeline(id);
            return Map.of("status", "ok", "message", "Pipeline retry started from first failed stage");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @PostMapping("/schemas/{id}/pipeline/cancel")
    public Map<String, String> cancelPipeline(@PathVariable String id) {
        pipelineService.cancelPipeline(id);
        return Map.of("status", "ok", "message", "Pipeline cancelled");
    }

    @GetMapping("/schemas/{id}/pipeline/status")
    public Map<String, Object> pipelineStatus(@PathVariable String id) {
        boolean running = pipelineService.isPipelineRunning(id);
        Map<String, String> results = pipelineService.getStageResults(id);
        return Map.of("running", running, "stageResults", results);
    }

    @PostMapping("/schemas/{id}/pipeline/default")
    public Map<String, Object> createDefaultPipeline(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        WorkflowSchema schema = schemaService.getSchema(id);
        if (schema == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found");
        }
        String appType = (String) body.getOrDefault("appType", "custom");
        String description = (String) body.getOrDefault("description", schema.getDescription());
        boolean tddEnabled = Boolean.TRUE.equals(body.getOrDefault("tddEnabled", false));
        Pipeline pipeline = PipelineService.createDefaultPipeline(appType, description);
        pipeline.setTddEnabled(tddEnabled);
        PipelineService.expandTddStages(pipeline);
        schema.setPipeline(pipeline);

        // Use user's default model — execution engine handles routing/availability
        String globalModel = settingsService.getGlobalDefaultModel();
        schema.setDefaultModel(globalModel != null && !globalModel.isBlank()
                ? globalModel : "");
        if (schema.getPipeline() != null && schema.getPipeline().getStages() != null) {
            for (var stage : schema.getPipeline().getStages()) {
                if (stage.getModel() == null || stage.getModel().isBlank()) {
                    stage.setModel(schema.getDefaultModel());
                }
            }
        }

        schemaService.updateSchema(id, schema);
        return Map.of("status", "ok", "pipeline", tddEnabled ? "TDD pipeline created" : "Default pipeline created");
    }

}
