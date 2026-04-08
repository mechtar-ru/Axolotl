package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.Agent;
import com.agent.orchestrator.model.WorkflowSchema;
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
    
    public AgentController(AgentService agentService, SchemaService schemaService) {
        this.agentService = agentService;
        this.schemaService = schemaService;
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
    
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "message", "Axolotl работает!");
    }
}
