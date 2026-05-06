package com.agent.orchestrator.graph.api;

import com.agent.orchestrator.graph.GraphMemoryService;
import com.agent.orchestrator.graph.loader.CodebaseLoader;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph")
public class GraphController {
    
    private final GraphMemoryService graphMemoryService;
    private final CodebaseLoader codebaseLoader;
    
    public GraphController(GraphMemoryService graphMemoryService, CodebaseLoader codebaseLoader) {
        this.graphMemoryService = graphMemoryService;
        this.codebaseLoader = codebaseLoader;
    }
    
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return graphMemoryService.getStats();
    }
    
    @GetMapping("/search/classes")
    public List<?> searchClasses(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        var results = graphMemoryService.searchClasses(keyword, limit);
        return results != null ? results : List.of();
    }
    
    @GetMapping("/search/methods")
    public List<?> searchMethods(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        var results = graphMemoryService.searchMethods(keyword, limit);
        return results != null ? results : List.of();
    }
    
    @GetMapping("/search/decisions")
    public List<?> searchDecisions(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        var results = graphMemoryService.searchDecisions(keyword, limit);
        return results != null ? results : List.of();
    }
    
    @GetMapping("/decisions/recent")
    public List<?> recentDecisions(
            @RequestParam(defaultValue = "10") int limit) {
        return graphMemoryService.findRecentDecisions(limit);
    }
    
    @PostMapping("/decisions")
    public Map<String, Object> addDecision(@RequestBody Map<String, String> payload) {
        String title = payload.get("title");
        String description = payload.get("description");
        String rationale = payload.get("rationale");
        String decidedBy = payload.get("decidedBy");
        String priority = payload.getOrDefault("priority", "MEDIUM");
        
        var decision = graphMemoryService.addDecision(title, description, rationale, decidedBy, priority);
        
        return Map.of(
            "id", decision.getId(),
            "title", decision.getTitle(),
            "status", decision.getStatus()
        );
    }
    
    @PostMapping("/decisions/{id}/link-class")
    public ResponseEntity<?> linkDecisionToClass(
            @PathVariable Long id,
            @RequestParam String classQualifiedName) {
        graphMemoryService.linkDecisionToClass(id, classQualifiedName);
        return ResponseEntity.ok(Map.of("linked", classQualifiedName));
    }
    
    @GetMapping("/context")
    public String buildContext(
            @RequestParam String query,
            @RequestParam(defaultValue = "2") int depth) {
        return graphMemoryService.buildContext(query, depth);
    }
    
    @PostMapping("/load-codebase")
    public Map<String, Object> loadCodebase() {
        int count = codebaseLoader.loadBackend();
        return Map.of("loaded", count);
    }
}