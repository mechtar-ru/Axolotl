package com.agent.orchestrator.graph.scheduler;

import com.agent.orchestrator.graph.model.CodeClass;
import com.agent.orchestrator.graph.repository.CodeClassRepository;
import com.agent.orchestrator.graph.repository.CodeMethodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchPlannerTest {

    @Mock
    private CodeClassRepository classRepo;

    @Mock
    private CodeMethodRepository methodRepo;

    private BatchPlanner batchPlanner;

    @BeforeEach
    void setUp() {
        batchPlanner = new BatchPlanner(classRepo, methodRepo);
    }

    @Test
    void testComputeTiers_returnsWaveStructure() {
        CodeClass classA = new CodeClass("A", "com.example.A", "com.example");
        CodeClass classB = new CodeClass("B", "com.example.B", "com.example");
        CodeClass classC = new CodeClass("C", "com.example.C", "com.example");

        classB.setDependencies(Set.of(classA));
        classC.setDependencies(Set.of(classA, classB));

        when(classRepo.findAll()).thenReturn(List.of(classA, classB, classC));

        BatchPlanner.BatchPlan plan = batchPlanner.computeImportTiers();

        assertNotNull(plan);
        assertTrue(plan.waves().size() >= 2, "Should have at least 2 tiers");
        assertTrue(plan.totalEntities() > 0);
    }

    @Test
    void testFindUnblockedEdits_returnsEligibleJobs() {
        CodeClass classA = new CodeClass("Test", "com.example.Test", "com.example");
        classA.setHash("abc123");

        when(classRepo.findAll()).thenReturn(List.of(classA));

        Set<String> completed = Set.of("xyz789");
        List<BatchPlanner.BatchJob> jobs = batchPlanner.findUnblockedEdits(completed);

        assertFalse(jobs.isEmpty());
        assertTrue(jobs.stream().anyMatch(j -> j.hash().equals("abc123")));
    }

    @Test
    void testPlanRenameRefactoring() {
        when(classRepo.findByNameContaining("Old")).thenReturn(List.of(
                new CodeClass("Old", "com.example.Old", "com.example")
        ));

        List<BatchPlanner.BatchJob> jobs = batchPlanner.planRenameRefactoring("Old", "New");

        assertFalse(jobs.isEmpty());
    }

    @Test
    void testAnalyzeImpact() {
        CodeClass changed = new CodeClass("Changed", "com.example.Changed", "com.example");
        changed.setHash("hash1");

        CodeClass dependent = new CodeClass("Dependent", "com.example.Dependent", "com.example");
        dependent.setDependencies(Set.of(changed));

        when(classRepo.findAll()).thenReturn(List.of(changed, dependent));

        Map<String, List<String>> impact = batchPlanner.analyzeChangeImpact(Set.of("hash1"));

        assertNotNull(impact);
        assertTrue(impact.containsKey("hash1"));
    }
}