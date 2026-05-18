package com.agent.orchestrator.graph;

import com.agent.orchestrator.graph.model.*;
import com.agent.orchestrator.graph.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class UnifiedMemoryService {
    
    private static final Logger log = LoggerFactory.getLogger(UnifiedMemoryService.class);
    
    private final CodeClassRepository classRepo;
    private final CodeMethodRepository methodRepo;
    private final DecisionRepository decisionRepo;
    private final GraphMemoryService graphService;
    
    @Value("${axolotl.graph.enabled:true}")
    private boolean useGraph;
    
    public UnifiedMemoryService(
            CodeClassRepository classRepo,
            CodeMethodRepository methodRepo,
            DecisionRepository decisionRepo,
            GraphMemoryService graphService) {
        this.classRepo = classRepo;
        this.methodRepo = methodRepo;
        this.decisionRepo = decisionRepo;
        this.graphService = graphService;
    }
    
    /**
     * Store execution result - save to both MemPalace (for backward) and Neo4j (for queries).
     */
    @Transactional
    public void storeExecutionResult(String schemaId, String summary, String result, String nodeName) {
        // Store decision in Neo4j
        if (useGraph) {
            try {
                Decision decision = new Decision(
                    "Execution: " + nodeName,
                    "Schema " + schemaId + " executed",
                    summary
                );
                decision.setStatus("accepted");
                decision.setDecidedAt(Instant.now());
                decision.setSource("execution");
                decisionRepo.save(decision);
                log.debug("Stored execution in Neo4j: {}", nodeName);
            } catch (Exception e) {
                log.warn("Failed to store in Neo4j: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Store agent trajectory.
     */
    @Transactional
    public void storeTrajectory(String taskDescription, String memoryEntry) {
        if (useGraph) {
            try {
                Decision d = new Decision(
                    "Agent trajectory",
                    memoryEntry,
                    "Task: " + taskDescription
                );
                d.setStatus("accepted");
                d.setDecidedAt(Instant.now());
                d.setSource("agent");
                decisionRepo.save(d);
            } catch (Exception e) {
                log.warn("Failed to store trajectory: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Store skill pattern.
     */
    @Transactional
    public void storeSkillPattern(String skillName, String pattern, String description) {
        if (useGraph) {
            try {
                Decision d = new Decision(
                    "Skill: " + skillName,
                    description,
                    pattern
                );
                d.setStatus("accepted");
                d.setDecidedAt(Instant.now());
                d.setSource("skill");
                d.setPriority("HIGH");
                decisionRepo.save(d);
            } catch (Exception e) {
                log.warn("Failed to store skill: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Search across both MemPalace (via graph) and direct queries.
     */
    public List<Decision> searchDecisions(String query, int limit) {
        if (!useGraph) return List.of();
        return decisionRepo.search(query, limit);
    }
    
    /**
     * Get relevant context for agent.
     */
    public String getContextForAgent(String query) {
        if (!useGraph) return "";
        return graphService.buildContext(query, 2);
    }
    
    /**
     * Get class info for reference.
     */
    public List<CodeClass> findClasses(String keyword, int limit) {
        if (!useGraph) return List.of();
        return classRepo.search(keyword, limit);
    }
    
    /**
     * Get method info.
     */
    public List<CodeMethod> findMethods(String keyword, int limit) {
        if (!useGraph) return List.of();
        return methodRepo.search(keyword, limit);
    }
    
    /**
     * Check if graph is available.
     */
    public boolean isGraphEnabled() {
        return useGraph;
    }
    
    public Map<String, Object> getStats() {
        return Map.of(
            "graphEnabled", useGraph,
            "classes", useGraph ? classRepo.count() : 0,
            "methods", useGraph ? methodRepo.count() : 0,
            "decisions", useGraph ? decisionRepo.count() : 0
        );
    }
}