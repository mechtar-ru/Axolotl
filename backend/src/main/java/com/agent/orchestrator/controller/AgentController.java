package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.Agent;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.ExecutionRecord;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.SchemaValidationResult;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.service.SchemaValidationException;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.service.AgentService;
import com.agent.orchestrator.service.PlanningService;
import com.agent.orchestrator.service.SchemaService;
import com.agent.orchestrator.service.SettingsService;
import com.agent.orchestrator.repository.ExecutionRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request body for POST /schemas/{id}/execute
 */
record ExecuteRequest(String sessionInput) {}

@RestController
@RequestMapping("/api")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final SchemaService schemaService;

    private final LlmService llmService;
    private final MemPalaceClient memPalaceClient;
    private final PlanningService planningService;
    private final SettingsService settingsService;
    private final ExecutionRepository executionRepository;

    public AgentController(AgentService agentService, SchemaService schemaService,
                           LlmService llmService, MemPalaceClient memPalaceClient,
                           PlanningService planningService, SettingsService settingsService,
                           ExecutionRepository executionRepository) {
        this.agentService = agentService;
        this.schemaService = schemaService;
        this.llmService = llmService;
        this.memPalaceClient = memPalaceClient;
        this.planningService = planningService;
        this.settingsService = settingsService;
        this.executionRepository = executionRepository;
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

    @GetMapping("/schemas/groups")
    public Map<String, List<WorkflowSchema>> getSchemaGroups() {
        String userId = getCurrentUserId();
        return schemaService.getSchemasGrouped(userId);
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

    @PostMapping("/schemas/batch-delete")
    public void batchDeleteSchemas(@RequestBody Map<String, List<String>> body) {
        List<String> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids list is required");
        }
        String userId = getCurrentUserId();
        // Verify user owns all schemas
        List<WorkflowSchema> allSchemas = schemaService.getSchemasByUserId(userId);
        Set<String> userSchemaIds = allSchemas.stream().map(WorkflowSchema::getId).collect(Collectors.toSet());
        for (String id : ids) {
            if (!userSchemaIds.contains(id)) {
                throw new SecurityException("Schema " + id + " does not belong to user");
            }
        }
        schemaService.batchDeleteSchemas(ids);
    }

    @GetMapping("/schemas/test-schemas")
    public List<WorkflowSchema> getTestSchemas() {
        String userId = getCurrentUserId();
        List<WorkflowSchema> all = schemaService.getSchemasByUserId(userId);
        return all.stream().filter(SchemaService::isTestSchema).collect(Collectors.toList());
    }

    @GetMapping("/schemas/recent")
    public List<WorkflowSchema> getRecentSchemas(@RequestParam(defaultValue = "10") int limit) {
        String userId = getCurrentUserId();
        return schemaService.getRecentSchemas(userId, limit);
    }

    @GetMapping("/schemas/{id}/export")
    public WorkflowSchema exportSchema(@PathVariable String id) {
        return schemaService.getSchema(id);
    }

    @PostMapping("/schemas/import")
    public WorkflowSchema importSchema(@RequestBody WorkflowSchema schema) {
        String userId = getCurrentUserId();
        return schemaService.importSchema(schema, userId);
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
    public ResponseEntity<Map<String, Object>> executeSchema(
            @PathVariable String id,
            @RequestBody(required = false) ExecuteRequest body,
            @RequestParam(defaultValue = "EXECUTE") ExecutionMode mode,
            jakarta.servlet.http.HttpServletRequest request) {
        String principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : "anonymous";
        log.info("executeSchema called for schema={} mode={} principal={} remoteAddr={}", id, mode, principal,
                request.getRemoteAddr());

        try {
            schemaService.executeSchema(id, body != null ? body.sessionInput() : null);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "started");
            result.put("schemaId", id);
            result.put("mode", mode.name());
            return ResponseEntity.ok(result);
        } catch (SchemaValidationException e) {
            log.warn("Schema execution blocked by validation: {} error(s)", e.getValidationResult().getErrors().size());
            Map<String, Object> result = new HashMap<>();
            result.put("status", "validation_error");
            result.put("validation", e.getValidationResult());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/schemas/{id}/validate")
    public SchemaValidationResult validateSchema(@PathVariable String id) {
        return schemaService.validateSchema(id);
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
    public Map<String, String> resumeExecution(
            @PathVariable String id,
            @RequestParam(required = false) String runId) {
        schemaService.resumeExecution(id, runId);
        return Map.of("status", "resumed");
    }

    @GetMapping("/schemas/{id}/runs/{runId}/nodes")
    public List<NodeExecution> getRunNodes(@PathVariable String id, @PathVariable String runId) {
        return schemaService.getExecutionNodes(runId);
    }

    @PostMapping("/schemas/{id}/cleanup-runs")
    public Map<String, Object> cleanupRuns(@PathVariable String id) {
        int released = executionRepository.releaseStaleRuns(id);
        return Map.of("status", "ok", "released", released);
    }

    @DeleteMapping("/schemas/{id}/runs/{runId}")
    public Map<String, String> deleteRun(@PathVariable String id, @PathVariable String runId) {
        executionRepository.deleteRun(runId);
        return Map.of("status", "ok", "message", "Run deleted");
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
            @RequestParam String nodeId,
            @RequestParam(required = false) String schemaId) {
        String resolvedSchemaId = schemaId != null ? schemaId : executionId;
        schemaService.handleReviewApprove(executionId, nodeId, resolvedSchemaId);
        log.info("Review approved for execution {} node {} schema {}", executionId, nodeId, resolvedSchemaId);
        return Map.of("status", "ok", "message", "Plan approved, resuming execution");
    }

    @PostMapping("/execution/{executionId}/reject")
    public Map<String, String> rejectReview(
            @PathVariable String executionId,
            @RequestParam String nodeId,
            @RequestParam(required = false) String schemaId) {
        String resolvedSchemaId = schemaId != null ? schemaId : executionId;
        schemaService.handleReviewReject(executionId, nodeId, resolvedSchemaId);
        log.info("Review rejected for execution {} node {} schema {}", executionId, nodeId, resolvedSchemaId);
        return Map.of("status", "ok", "message", "Plan rejected, node failed");
    }

    @PostMapping("/execution/{executionId}/install-deps")
    public Map<String, Object> installDeps(
            @PathVariable String executionId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> deps = (List<String>) body.getOrDefault("deps", List.of());
        String schemaId = (String) body.getOrDefault("schemaId", executionId);

        if (deps.isEmpty()) {
            return Map.of("status", "error", "message", "No deps specified");
        }

        log.info("Installing deps for execution {}: {}", executionId, deps);
        List<Map<String, Object>> results = new ArrayList<>();

        for (String dep : deps) {
            Map<String, Object> result = new HashMap<>();
            result.put("dep", dep);
            result.put("status", "installing");
            try {
                List<String> cmd = buildInstallCommand(dep);
                if (cmd.isEmpty()) {
                    result.put("status", "error");
                    result.put("message", "Unknown dependency: " + dep);
                    results.add(result);
                    continue;
                }
                log.info("Running install command: {}", String.join(" ", cmd));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                boolean finished = proc.waitFor(300, TimeUnit.SECONDS);
                String output = new String(proc.getInputStream().readAllBytes());
                if (finished && proc.exitValue() == 0) {
                    result.put("status", "ok");
                    result.put("message", "Installed successfully");
                } else if (!finished) {
                    proc.destroyForcibly();
                    result.put("status", "error");
                    result.put("message", "Install timed out after 300s");
                } else {
                    result.put("status", "error");
                    result.put("message", output.trim());
                }
            } catch (IllegalArgumentException e) {
                result.put("status", "error");
                result.put("message", e.getMessage());
            } catch (Exception e) {
                result.put("status", "error");
                result.put("message", e.getMessage());
            }
            results.add(result);
        }

        boolean allOk = results.stream().allMatch(r -> "ok".equals(r.get("status")));
        return Map.of(
            "status", allOk ? "ok" : "partial",
            "results", results
        );
    }

    @PostMapping("/execution/{executionId}/approve-diffs")
    public Map<String, String> approveDiffs(
            @PathVariable String executionId,
            @RequestParam String nodeId,
            @RequestParam(required = false) String schemaId) {
        schemaService.handleDiffsApprove(executionId, nodeId);
        log.info("Diff review approved for execution {} node {}", executionId, nodeId);
        return Map.of("status", "ok", "message", "File changes approved, resuming pipeline");
    }

    @PostMapping("/execution/{executionId}/reject-diffs")
    public Map<String, String> rejectDiffs(
            @PathVariable String executionId,
            @RequestParam String nodeId,
            @RequestParam(required = false) String schemaId) {
        schemaService.handleDiffsReject(executionId, nodeId);
        log.info("Diff review rejected for execution {} node {} — files restored", executionId, nodeId);
        return Map.of("status", "ok", "message", "File changes rejected, original files restored");
    }

    private List<String> buildInstallCommand(String dep) {
        if (dep == null || dep.isBlank()) return List.of();
        // Validate dep name — only allow package-name chars
        if (!dep.matches("^[a-zA-Z0-9_\\-]+$")) {
            throw new IllegalArgumentException("Invalid dependency name: " + dep);
        }
        if (dep.toLowerCase().contains("flutter")) {
            return List.of("which", "flutter");
        }
        if (dep.toLowerCase().contains("android") || dep.toLowerCase().contains("sdkmanager")) {
            return List.of("echo", "Android SDK requires manual setup. Install Android Studio from https://developer.android.com/studio");
        }
        if (dep.toLowerCase().contains("xcode")) {
            // Show the App Store URL for manual install
            try {
                new ProcessBuilder("open", "https://apps.apple.com/app/xcode/id497799835")
                    .start();
            } catch (Exception ignored) {
                // non-fatal
            }
            return List.of("xcode-select", "-p");
        }
        if (dep.toLowerCase().contains("cocoapods") || dep.toLowerCase().contains("pod")) {
            return List.of("which", "pod");
        }
        return List.of();
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
            String response = llmService.chat(model, null, message, null).text();
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
        // P14: SSRF protection — only http/https schemes
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return Map.of("status", "error", "error", "Only http/https URLs allowed");
        }
        // P14: Block private IP ranges
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (host == null || host.equals("localhost") || host.equals("127.0.0.1")
                    || host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.16.")) {
                return Map.of("status", "error", "error", "Access to private networks blocked");
            }
        } catch (Exception e) {
            return Map.of("status", "error", "error", "Invalid URL");
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

}
