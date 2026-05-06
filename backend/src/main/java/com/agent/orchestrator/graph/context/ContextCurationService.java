package com.agent.orchestrator.graph.context;

import com.agent.orchestrator.graph.model.CodeClass;
import com.agent.orchestrator.graph.model.CodeMethod;
import com.agent.orchestrator.graph.repository.CodeClassRepository;
import com.agent.orchestrator.graph.repository.CodeMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContextCurationService {

    private static final Logger log = LoggerFactory.getLogger(ContextCurationService.class);

    private static final int DEFAULT_TOKEN_BUDGET = 2000;
    private static final double TOKEN_PER_CHAR = 0.25;

    private final CodeClassRepository classRepo;
    private final CodeMethodRepository methodRepo;

    public ContextCurationService(CodeClassRepository classRepo, CodeMethodRepository methodRepo) {
        this.classRepo = classRepo;
        this.methodRepo = methodRepo;
    }

    public record CurationResult(
            String prompt,
            int tokenCount,
            List<String> classHashes,
            List<String> methodHashes,
            String strategy
    ) {}

    public CurationResult curateForQuery(String query, int tokenBudget, List<String> recentHashes) {
        int budget = tokenBudget > 0 ? tokenBudget : DEFAULT_TOKEN_BUDGET;
        int maxChars = (int) (budget / TOKEN_PER_CHAR);

        String[] tokens = query.toLowerCase().split("\\s+");

        List<RankedEntity> ranked = new ArrayList<>();

        for (String token : tokens) {
            List<CodeClass> classes = classRepo.findByNameContainingOrQualifiedNameContaining(token, token);
            for (CodeClass c : classes) {
                ranked.add(new RankedEntity(
                        "class",
                        c.getHash(),
                        c.getQualifiedName(),
                        calculateRelevance(query, c),
                        calculateCentrality(c),
                        c
                ));
            }

            List<CodeMethod> methods = methodRepo.findByNameContaining(token);
            for (CodeMethod m : methods) {
                ranked.add(new RankedEntity(
                        "method",
                        m.getHash(),
                        m.getSignature(),
                        calculateRelevance(query, m),
                        calculateMethodCentrality(m),
                        null
                ));
            }
        }

        List<RankedEntity> boosted = new ArrayList<>();
        for (RankedEntity e : ranked) {
            if (recentHashes.contains(e.hash())) {
                boosted.add(new RankedEntity(
                        e.type(),
                        e.hash(),
                        e.qualifiedName(),
                        e.relevance() + 0.5,
                        e.centrality(),
                        e.classEntity()
                ));
            } else {
                boosted.add(e);
            }
        }
        ranked = boosted;

        ranked.sort((a, b) -> {
            double scoreA = a.relevance() * 0.6 + a.centrality() * 0.4;
            double scoreB = b.relevance() * 0.6 + b.centrality() * 0.4;
            return Double.compare(scoreB, scoreA);
        });

        StringBuilder prompt = new StringBuilder();
        prompt.append("Context for query: ").append(query).append("\n\n");

        int currentChars = prompt.length();
        List<String> classHashes = new ArrayList<>();
        List<String> methodHashes = new ArrayList<>();

        for (RankedEntity e : ranked) {
            if (currentChars >= maxChars) break;

            String entityText = formatEntity(e);
            if (currentChars + entityText.length() > maxChars) {
                int remaining = maxChars - currentChars;
                if (remaining < 100) break;
                entityText = entityText.substring(0, remaining) + "...\n";
            }

            prompt.append(entityText).append("\n");
            currentChars += entityText.length() + 1;

            if ("class".equals(e.type())) {
                classHashes.add(e.hash());
            } else {
                methodHashes.add(e.hash());
            }
        }

        int estimatedTokens = (int) (currentChars * TOKEN_PER_CHAR);

        return new CurationResult(
                prompt.toString(),
                estimatedTokens,
                classHashes,
                methodHashes,
                "hybrid_relevance_centrality"
        );
    }

    private double calculateRelevance(String query, CodeClass c) {
        String q = query.toLowerCase();
        double score = 0.0;
        if (c.getName() != null && c.getName().toLowerCase().contains(q)) score += 0.5;
        if (c.getQualifiedName() != null && c.getQualifiedName().toLowerCase().contains(q)) score += 0.3;
        if (c.getDescription() != null && c.getDescription().toLowerCase().contains(q)) score += 0.2;
        return score;
    }

    private double calculateRelevance(String query, CodeMethod m) {
        String q = query.toLowerCase();
        double score = 0.0;
        if (m.getName() != null && m.getName().toLowerCase().contains(q)) score += 0.5;
        if (m.getDescription() != null && m.getDescription().toLowerCase().contains(q)) score += 0.3;
        return score;
    }

    private double calculateCentrality(CodeClass c) {
        int depCount = c.getDependencies() != null ? c.getDependencies().size() : 0;
        return Math.min(1.0, depCount / 10.0);
    }

    private double calculateMethodCentrality(CodeMethod m) {
        int callCount = m.getCalledBy() != null ? m.getCalledBy().size() : 0;
        return Math.min(1.0, callCount / 5.0);
    }

    private String formatEntity(RankedEntity e) {
        if ("class".equals(e.type())) {
            CodeClass c = e.classEntity();
            return """
                ## Class: %s (%s)
                Package: %s
                Methods: %d
                Fields: %d
                %s
                """.formatted(
                        c.getName(),
                        c.getHash(),
                        c.getPackageName(),
                        c.getMethods() != null ? c.getMethods().size() : 0,
                        c.getFields() != null ? c.getFields().size() : 0,
                        c.getDescription() != null ? c.getDescription() : ""
                );
        } else {
            return """
                ## Method: %s
                Hash: %s
                Returns: %s
                """.formatted(
                        e.qualifiedName(),
                        e.hash(),
                        "N/A"
                );
        }
    }

    public record RankedEntity(
            String type,
            String hash,
            String qualifiedName,
            double relevance,
            double centrality,
            CodeClass classEntity
    ) {}
}