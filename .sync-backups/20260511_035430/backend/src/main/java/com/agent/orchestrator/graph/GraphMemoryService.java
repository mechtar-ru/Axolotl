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
public class GraphMemoryService {
    
    private static final Logger log = LoggerFactory.getLogger(GraphMemoryService.class);
    
    private final CodePackageRepository packageRepo;
    private final CodeClassRepository classRepo;
    private final CodeMethodRepository methodRepo;
    private final DecisionRepository decisionRepo;
    
    @Value("${axolotl.graph.enabled:true}")
    private boolean enabled;
    
    public GraphMemoryService(
            CodePackageRepository packageRepo,
            CodeClassRepository classRepo,
            CodeMethodRepository methodRepo,
            DecisionRepository decisionRepo) {
        this.packageRepo = packageRepo;
        this.classRepo = classRepo;
        this.methodRepo = methodRepo;
        this.decisionRepo = decisionRepo;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    // ==================== Decision Operations ====================
    
    @Transactional
    public Decision addDecision(String title, String description, String rationale,
                             String decidedBy, String priority) {
        Decision decision = new Decision(title, description, rationale);
        decision.setDecidedBy(decidedBy);
        decision.setPriority(priority);
        decision.setStatus("accepted");
        decision.setDecidedAt(Instant.now());
        
        return decisionRepo.save(decision);
    }
    
    @Transactional
    public void linkDecisionToClass(Long decisionId, String classQualifiedName) {
        Decision decision = decisionRepo.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Decision not found: " + decisionId));
        CodeClass clazz = classRepo.findByQualifiedName(classQualifiedName)
                .orElse(null);
        
        if (clazz != null) {
            decision.getImpactedClasses().add(clazz);
            decisionRepo.save(decision);
            log.info("Linked decision {} to class {}", decisionId, classQualifiedName);
        } else {
            log.warn("Class not found for linking: {}", classQualifiedName);
        }
    }
    
    @Transactional
    public void linkDecisionToDecision(Long fromId, Long toId) {
        Decision from = decisionRepo.findById(fromId).orElse(null);
        Decision to = decisionRepo.findById(toId).orElse(null);
        
        if (from != null && to != null) {
            from.getRelatedDecisions().add(to);
            decisionRepo.save(from);
        }
    }
    
    // ==================== Search Operations ====================
    
    public List<CodeClass> findClassDependencies(String qualifiedName) {
        return classRepo.findDependencies(qualifiedName);
    }
    
    public List<CodeMethod> findMethodCallers(String signature) {
        return methodRepo.findCallers(signature);
    }
    
    public List<CodeMethod> findMethodCallees(String signature) {
        return methodRepo.findCallees(signature);
    }
    
    public List<Decision> searchDecisions(String keyword, int limit) {
        return decisionRepo.search(keyword, limit);
    }
    
    public List<Decision> findDecisionsForClass(String classQualifiedName) {
        return decisionRepo.findByAffectedClass(classQualifiedName);
    }
    
    public List<Decision> findRecentDecisions(int limit) {
        return decisionRepo.findByStatusSince("accepted", Instant.now().minusSeconds(86400 * 30));
    }
    
    public List<CodeClass> searchClasses(String keyword, int limit) {
        return classRepo.search(keyword, limit);
    }
    
    public List<CodeMethod> searchMethods(String keyword, int limit) {
        return methodRepo.search(keyword, limit);
    }
    
    public List<CodeClass> findRecentlyImpactedClasses(int limit) {
        return classRepo.findRecentlyImpactedClasses(limit);
    }
    
    // ==================== Context Building ====================
    
    /**
     * Build context for AI agent: find code elements and decisions related to query.
     */
    public String buildContext(String query, int depth) {
        if (!enabled) return "";
        
        StringBuilder sb = new StringBuilder();
        sb.append("[GRAPH MEMORY - ").append(query).append("]\n\n");
        
        // Search for relevant decisions
        List<Decision> decisions = decisionRepo.search(query, 5);
        if (!decisions.isEmpty()) {
            sb.append("## Relevant Decisions\n");
            for (Decision d : decisions) {
                sb.append(String.format("### %s [%s]\n", d.getTitle(), d.getStatus().toUpperCase()));
                sb.append(d.getDescription()).append("\n");
                if (d.getRationale() != null) {
                    sb.append("**Rationale:** ").append(d.getRationale()).append("\n");
                }
                if (!d.getImpactedClasses().isEmpty()) {
                    sb.append("**Impacted Classes:** ");
                    sb.append(d.getImpactedClasses().stream()
                            .map(CodeClass::getQualifiedName)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("")).append("\n");
                }
                sb.append("\n");
            }
        }
        
        // Search for code elements
        List<CodeClass> classes = classRepo.search(query, 5);
        if (!classes.isEmpty()) {
            sb.append("## Relevant Classes\n");
            for (CodeClass c : classes) {
                sb.append(String.format("- `%s` (%s)\n", c.getQualifiedName(), c.getPackageName()));
            }
        }
        
        List<CodeMethod> methods = methodRepo.search(query, 5);
        if (!methods.isEmpty()) {
            sb.append("## Relevant Methods\n");
            for (CodeMethod m : methods) {
                sb.append(String.format("- `%s` -> %s\n", m.getName(), m.getReturnType()));
            }
        }
        
        sb.append("\n[END GRAPH MEMORY]");
        return sb.toString();
    }
    
    // ==================== Stats ====================
    
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", enabled);
        
        try {
            long packageCount = packageRepo.count();
            long classCount = classRepo.count();
            long methodCount = methodRepo.count();
            long decisionCount = decisionRepo.count();
            
            stats.put("packages", packageCount);
            stats.put("classes", classCount);
            stats.put("methods", methodCount);
            stats.put("decisions", decisionCount);
            stats.put("total", packageCount + classCount + methodCount + decisionCount);
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }
        
        return stats;
    }
}