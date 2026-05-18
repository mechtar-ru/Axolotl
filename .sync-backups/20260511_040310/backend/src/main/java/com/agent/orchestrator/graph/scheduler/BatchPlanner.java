package com.agent.orchestrator.graph.scheduler;

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
public class BatchPlanner {

    private static final Logger log = LoggerFactory.getLogger(BatchPlanner.class);

    private final CodeClassRepository classRepo;
    private final CodeMethodRepository methodRepo;

    public BatchPlanner(CodeClassRepository classRepo, CodeMethodRepository methodRepo) {
        this.classRepo = classRepo;
        this.methodRepo = methodRepo;
    }

    public record BatchJob(String entityType, String hash, String qualifiedName, int tier) {}

    public record BatchPlan(List<List<BatchJob>> waves, int totalEntities) {}

    public BatchPlan computeImportTiers() {
        List<CodeClass> allClasses = classRepo.findAll();
        Map<String, CodeClass> classMap = allClasses.stream()
                .collect(Collectors.toMap(CodeClass::getQualifiedName, c -> c));

        Map<String, Set<String>> dependencies = new HashMap<>();
        for (CodeClass c : allClasses) {
            dependencies.put(c.getQualifiedName(),
                    c.getDependencies().stream()
                            .map(CodeClass::getQualifiedName)
                            .collect(Collectors.toSet()));
        }

        Map<String, Integer> tierMap = new HashMap<>();
        Set<String> processed = new HashSet<>();

        int currentTier = 0;
        while (processed.size() < allClasses.size()) {
            Set<String> nextTier = new HashSet<>();

            for (CodeClass c : allClasses) {
                String qn = c.getQualifiedName();
                if (processed.contains(qn)) continue;

                Set<String> deps = dependencies.getOrDefault(qn, Set.of());
                boolean allDepsProcessed = deps.isEmpty() ||
                        deps.stream().allMatch(processed::contains);

                if (allDepsProcessed) {
                    nextTier.add(qn);
                    tierMap.put(qn, currentTier);
                }
            }

            processed.addAll(nextTier);
            currentTier++;
        }

        List<List<BatchJob>> waves = new ArrayList<>();
        for (int i = 0; i < currentTier; i++) {
            final int tier = i;
            List<BatchJob> wave = tierMap.entrySet().stream()
                    .filter(e -> e.getValue() == tier)
                    .map(e -> new BatchJob("Class", e.getKey(), e.getKey(), tier))
                    .toList();
            waves.add(wave);
        }

        return new BatchPlan(waves, allClasses.size());
    }

    public List<BatchJob> findUnblockedEdits(Set<String> completedHashes) {
        List<CodeClass> classes = classRepo.findAll();
        List<BatchJob> jobs = new ArrayList<>();

        for (CodeClass c : classes) {
            if (completedHashes.contains(c.getHash())) continue;

            Set<String> deps = c.getDependencies().stream()
                    .map(CodeClass::getHash)
                    .collect(Collectors.toSet());

            boolean blocked = deps.stream().anyMatch(d -> !completedHashes.contains(d));
            if (!blocked) {
                jobs.add(new BatchJob("Class", c.getHash(), c.getQualifiedName(), 0));
            }
        }

        return jobs;
    }

    public Map<String, List<String>> analyzeChangeImpact(Set<String> changedHashes) {
        Map<String, List<String>> impact = new HashMap<>();
        List<CodeClass> allClasses = classRepo.findAll();

        for (String hash : changedHashes) {
            CodeClass changed = allClasses.stream()
                    .filter(c -> hash.equals(c.getHash()))
                    .findFirst()
                    .orElse(null);

            if (changed == null) continue;

            List<String> affected = new ArrayList<>();
            for (CodeClass c : allClasses) {
                if (c.getDependencies() != null) {
                    for (CodeClass dep : c.getDependencies()) {
                        if (dep.getHash().equals(hash)) {
                            affected.add(c.getQualifiedName());
                            break;
                        }
                    }
                }
            }
            impact.put(hash, affected);
        }

        return impact;
    }

    public List<BatchJob> planRenameRefactoring(String oldName, String newName) {
        List<CodeClass> classes = classRepo.findByNameContaining(oldName);
        List<BatchJob> jobs = new ArrayList<>();

        for (CodeClass c : classes) {
            jobs.add(new BatchJob("Class", c.getHash(), c.getQualifiedName(), 0));
        }

        List<CodeMethod> methods = methodRepo.findByNameContaining(oldName);
        for (CodeMethod m : methods) {
            jobs.add(new BatchJob("Method", m.getHash(), m.getSignature(), 0));
        }

        return jobs;
    }
}