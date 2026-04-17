package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.Agent;
import com.agent.orchestrator.model.ExecutionMode;
import com.agent.orchestrator.model.ExecutionRecord;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.service.AgentService;
import com.agent.orchestrator.service.SchemaService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentService agentService;
    private final SchemaService schemaService;
    private final LlmService llmService;
    private final MemPalaceClient memPalaceClient;

    public AgentController(AgentService agentService, SchemaService schemaService, LlmService llmService, MemPalaceClient memPalaceClient) {
        this.agentService = agentService;
        this.schemaService = schemaService;
        this.llmService = llmService;
        this.memPalaceClient = memPalaceClient;
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
        schemaService.executeSchema(id, mode);
        return Map.of("status", "started", "schemaId", id, "mode", mode.name());
    }

    @PostMapping("/schemas/{id}/stop")
    public Map<String, String> stopSchema(@PathVariable String id) {
        schemaService.cancelExecution(id);
        return Map.of("status", "stopped", "schemaId", id);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("message", "Axolotl работает!");
        result.put("ollama", llmService.isProviderAvailable("ollama"));
        result.put("spring-ai", llmService.isProviderAvailable("spring-ai"));
        return result;
    }

    @PostMapping("/llm/test")
    public Map<String, Object> testLlm(@RequestParam(defaultValue = "spring-ai") String provider, 
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

    // === Settings ===

    @GetMapping("/settings/providers")
    public List<Map<String, Object>> getProviders() {
        return llmService.getProvidersInfo();
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

    @GetMapping("/outputs/{schemaId}/{nodeId}")
    public Map<String, String> getOutputFile(@PathVariable String schemaId, @PathVariable String nodeId) {
        String content = schemaService.getOutputFileContent(schemaId, nodeId);
        if (content == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return Map.of("content", content);
    }
}
