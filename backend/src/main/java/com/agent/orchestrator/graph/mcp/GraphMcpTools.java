package com.agent.orchestrator.graph.mcp;

import com.agent.orchestrator.graph.GraphMemoryService;
import com.agent.orchestrator.graph.model.Decision;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Component
public class GraphMcpTools {
    
    private final GraphMemoryService graphMemoryService;
    
    public GraphMcpTools(GraphMemoryService graphMemoryService) {
        this.graphMemoryService = graphMemoryService;
    }
    
    public String getName() {
        return "graph";
    }
    
    public Map<String, Object> searchDecisions(String keyword, int limit) {
        var decisions = graphMemoryService.searchDecisions(keyword, limit);
        List<Map<String, Object>> results = new ArrayList<>();
        
        for (var d : decisions) {
            results.add(Map.of(
                "id", d.getId(),
                "title", d.getTitle(),
                "description", d.getDescription(),
                "status", d.getStatus(),
                "priority", d.getPriority()
            ));
        }
        
        return Map.of("results", results, "count", results.size());
    }
    
    public Map<String, Object> addDecision(String title, String description, 
                                       String rationale, String priority) {
        var decision = graphMemoryService.addDecision(title, description, 
            rationale, "agent", priority);
        
        return Map.of(
            "id", decision.getId(),
            "title", decision.getTitle(),
            "status", decision.getStatus()
        );
    }
    
    public Map<String, Object> getContext(String query) {
        String context = graphMemoryService.buildContext(query, 2);
        return Map.of("context", context);
    }
    
    public Map<String, Object> getStats() {
        return graphMemoryService.getStats();
    }
}