package com.agent.orchestrator.graph.context;

import com.agent.orchestrator.graph.model.CodeClass;
import com.agent.orchestrator.graph.model.CodeMethod;
import com.agent.orchestrator.graph.repository.CodeClassRepository;
import com.agent.orchestrator.graph.repository.CodeMethodRepository;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContextCurationService {

    private static final Logger log = LoggerFactory.getLogger(ContextCurationService.class);

    private static final int DEFAULT_TOKEN_BUDGET = 2000;

    // Static jtokkit encoding instance — lazily initialized once.
    // If initialization fails (e.g. BPE rank file is missing), all calls fall back
    // to the heuristic regex tokenizer (countTokensFallback).
    private static final Encoding JTOKKIT_ENCODING;

    static {
        Encoding encoding = null;
        try {
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            encoding = registry.getEncodingForModel(ModelType.GPT_4);
            log.info("jtokkit encoding initialized: cl100k_base (GPT-4)");
        } catch (Exception e) {
            log.warn("Failed to initialize jtokkit encoding, heuristic fallback will be used", e);
        }
        JTOKKIT_ENCODING = encoding;
    }

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
            double scoreA = computeScore(a);
            double scoreB = computeScore(b);
            return Double.compare(scoreB, scoreA);
        });

        // Compute budget pressure: ratio of candidate tokens to budget
        int candidateTokens = 0;
        for (RankedEntity e : ranked) {
            candidateTokens += countTokens(formatEntity(e, false) + "\n");
        }
        double budgetPressure = candidateTokens > budget ? (double) candidateTokens / budget : 0.0;
        boolean condensed = budgetPressure > 2.0;

        StringBuilder prompt = new StringBuilder();
        prompt.append("Context for query: ").append(query);
        if (condensed) prompt.append(" (condensed)");
        prompt.append("\n\n");

        int runningTokens = countTokens(prompt.toString());
        List<String> classHashes = new ArrayList<>();
        List<String> methodHashes = new ArrayList<>();

        for (RankedEntity e : ranked) {
            String entityText = formatEntity(e, condensed);
            String appendedText = entityText + "\n";
            int entityTokens = countTokens(appendedText);

            if (runningTokens + entityTokens > budget) {
                break;
            }

            prompt.append(appendedText);
            runningTokens += entityTokens;

            if ("class".equals(e.type())) {
                classHashes.add(e.hash());
            } else {
                methodHashes.add(e.hash());
            }
        }

        int totalTokens = countTokens(prompt.toString());

        return new CurationResult(
                prompt.toString(),
                totalTokens,
                classHashes,
                methodHashes,
                condensed ? "adaptive_condensed" : "adaptive_full"
        );
    }

    private double calculateRelevance(String query, CodeClass c) {
        String q = query.toLowerCase();
        double score = 0.0;
        if (c.getName() != null && c.getName().toLowerCase().contains(q)) score += 0.5;
        if (c.getQualifiedName() != null && c.getQualifiedName().toLowerCase().contains(q)) score += 0.3;
        if (c.getDescription() != null && c.getDescription().toLowerCase().contains(q)) score += 0.2;
        if (c.getImports() != null && c.getImports().toLowerCase().contains(q)) score += 0.15;
        if (c.isInterface()) score += 0.1;
        return Math.min(1.25, score);
    }

    private double calculateRelevance(String query, CodeMethod m) {
        String q = query.toLowerCase();
        double score = 0.0;
        if (m.getName() != null && m.getName().toLowerCase().contains(q)) score += 0.5;
        if (m.getDescription() != null && m.getDescription().toLowerCase().contains(q)) score += 0.3;
        if (m.getBody() != null && m.getBody().toLowerCase().contains(q)) score += 0.15;
        if (m.getReturnType() != null && m.getReturnType().toLowerCase().contains(q)) score += 0.05;
        if (m.getParameters() != null && m.getParameters().toLowerCase().contains(q)) score += 0.1;
        return Math.min(1.1, score);
    }

    /**
     * Count tokens using jtokkit (real BPE tokenizer, cl100k_base / GPT-4 encoding).
     * Falls back to the heuristic regex tokenizer if jtokkit fails.
     *
     * @param text input text (may be null or empty)
     * @return token count (>= 0)
     */
    static int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        if (JTOKKIT_ENCODING != null) {
            try {
                return JTOKKIT_ENCODING.countTokens(text);
            } catch (Exception e) {
                log.warn("jtokkit countTokens failed for text ({} chars), falling back to heuristic",
                        text.length(), e);
            }
        }
        return countTokensFallback(text);
    }

    /**
     * Heuristic regex-based token counter (preserved for fallback).
     * Splits on whitespace and punctuation boundaries, estimates ceil(len/4) per segment.
     * This is the exact same logic from before the jtokkit migration.
     * Package-private so tests can verify fallback behavior.
     */
    static int countTokensFallback(String text) {
        if (text == null || text.isEmpty()) return 0;
        String[] parts = text.split("(?<=\\s)|(?=\\s)|(?<=\\W)|(?=\\W)");
        int count = 0;
        for (String part : parts) {
            if (!part.isBlank()) {
                int len = part.trim().length();
                if (len == 0) continue;
                count += Math.max(1, (len + 3) / 4);
            }
        }
        return Math.max(1, count);
    }

    private double computeScore(RankedEntity e) {
        if ("class".equals(e.type())) {
            CodeClass c = e.classEntity();
            if (c != null && c.isInterface()) {
                return e.relevance() * 0.7 + e.centrality() * 0.3;
            } else if (c != null && c.isAbstract()) {
                return e.relevance() * 0.65 + e.centrality() * 0.35;
            }
            return e.relevance() * 0.5 + e.centrality() * 0.5;
        }
        return e.relevance() * 0.6 + e.centrality() * 0.4;
    }

    private double calculateCentrality(CodeClass c) {
        int depCount = c.getDependencies() != null ? c.getDependencies().size() : 0;
        return Math.min(1.0, depCount / 10.0);
    }

    private double calculateMethodCentrality(CodeMethod m) {
        int callCount = m.getCalledBy() != null ? m.getCalledBy().size() : 0;
        return Math.min(1.0, callCount / 5.0);
    }

    private String formatEntity(RankedEntity e, boolean condensed) {
        if ("class".equals(e.type())) {
            CodeClass c = e.classEntity();
            if (c == null) return "";
            if (c.isInterface()) {
                if (condensed) {
                    return "## Interface: %s (%s)".formatted(c.getName(), c.getHash());
                }
                return """
                    ## Interface: %s (%s)
                    Package: %s
                    Methods: %d
                    %s
                    """.formatted(
                            c.getName(), c.getHash(), c.getPackageName(),
                            c.getMethods() != null ? c.getMethods().size() : 0,
                            c.getDescription() != null ? c.getDescription() : "");
            }
            if (c.isAbstract()) {
                if (condensed) {
                    return "## Abstract: %s (%s)".formatted(c.getName(), c.getHash());
                }
                return """
                    ## Abstract: %s (%s)
                    Package: %s
                    Methods: %d (abstract: varies)
                    Fields: %d
                    Dependencies: %d
                    %s
                    """.formatted(
                            c.getName(), c.getHash(), c.getPackageName(),
                            c.getMethods() != null ? c.getMethods().size() : 0,
                            c.getFields() != null ? c.getFields().size() : 0,
                            c.getDependencies() != null ? c.getDependencies().size() : 0,
                            c.getDescription() != null ? c.getDescription() : "");
            }
            if (condensed) {
                return "## Class: %s (%s) — %d methods, %d deps".formatted(
                        c.getName(), c.getHash(),
                        c.getMethods() != null ? c.getMethods().size() : 0,
                        c.getDependencies() != null ? c.getDependencies().size() : 0);
            }
            return """
                ## Class: %s (%s)
                Package: %s
                Methods: %d
                Fields: %d
                Dependencies: %d
                %s
                """.formatted(
                        c.getName(), c.getHash(), c.getPackageName(),
                        c.getMethods() != null ? c.getMethods().size() : 0,
                        c.getFields() != null ? c.getFields().size() : 0,
                        c.getDependencies() != null ? c.getDependencies().size() : 0,
                        c.getDescription() != null ? c.getDescription() : "");
        } else {
            if (condensed) {
                return "## Method: %s".formatted(e.qualifiedName());
            }
            return """
                ## Method: %s
                Hash: %s
                """.formatted(e.qualifiedName(), e.hash());
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
