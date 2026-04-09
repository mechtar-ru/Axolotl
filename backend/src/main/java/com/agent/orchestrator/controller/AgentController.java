package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.Agent;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.service.AgentService;
import com.agent.orchestrator.service.SchemaService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AgentController {

    private final AgentService agentService;
    private final SchemaService schemaService;
    private final LlmService llmService;

    public AgentController(AgentService agentService, SchemaService schemaService, LlmService llmService) {
        this.agentService = agentService;
        this.schemaService = schemaService;
        this.llmService = llmService;
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

    @GetMapping("/schemas")
    public List<WorkflowSchema> getAllSchemas() {
        return schemaService.getAllSchemas();
    }

    @GetMapping("/schemas/{id}")
    public WorkflowSchema getSchema(@PathVariable String id) {
        return schemaService.getSchema(id);
    }

    @PostMapping("/schemas")
    public WorkflowSchema createSchema(@RequestBody WorkflowSchema schema) {
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

    @PostMapping("/schemas/{id}/execute")
    public Map<String, String> executeSchema(@PathVariable String id) {
        schemaService.executeSchema(id);
        return Map.of("status", "started", "schemaId", id);
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
}
