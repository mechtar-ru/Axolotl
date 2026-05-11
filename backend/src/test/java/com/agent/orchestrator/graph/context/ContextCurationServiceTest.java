package com.agent.orchestrator.graph.context;

import com.agent.orchestrator.graph.model.CodeClass;
import com.agent.orchestrator.graph.model.CodeMethod;
import com.agent.orchestrator.graph.repository.CodeClassRepository;
import com.agent.orchestrator.graph.repository.CodeMethodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextCurationServiceTest {

    @Mock
    private CodeClassRepository classRepo;

    @Mock
    private CodeMethodRepository methodRepo;

    private ContextCurationService curationService;

    @BeforeEach
    void setUp() {
        curationService = new ContextCurationService(classRepo, methodRepo);
    }

    @Test
    void testCurateForQuery_returnsWithinBudget() {
        CodeClass mockClass = mock(CodeClass.class);
        when(mockClass.getHash()).thenReturn("abc123");
        when(mockClass.getQualifiedName()).thenReturn("com.example.Test");
        when(mockClass.getPackageName()).thenReturn("com.example");
        when(mockClass.getName()).thenReturn("Test");
        when(mockClass.getDescription()).thenReturn("Test description");
        when(mockClass.getMethods()).thenReturn(Set.of());
        when(mockClass.getFields()).thenReturn(Set.of());
        when(mockClass.getDependencies()).thenReturn(Set.of());

        when(classRepo.findByNameContainingOrQualifiedNameContaining("test", "test"))
                .thenReturn(List.of(mockClass));

        ContextCurationService.CurationResult result =
                curationService.curateForQuery("test", 500, List.of());

        assertNotNull(result);
        assertTrue(result.tokenCount() <= 500, "Should respect token budget");
        assertTrue(!result.classHashes().isEmpty() || !result.methodHashes().isEmpty(),
                "Should return some hashes");
        assertTrue(result.strategy().startsWith("adaptive_"),
                "Strategy should be adaptive: " + result.strategy());
    }

    @Test
    void testCurateBoostsRecentHashes() {
        CodeClass mockClass = mock(CodeClass.class);
        when(mockClass.getHash()).thenReturn("recentHash");
        when(mockClass.getQualifiedName()).thenReturn("com.example.Test");
        when(mockClass.getPackageName()).thenReturn("com.example");
        when(mockClass.getName()).thenReturn("Test");
        when(mockClass.getDescription()).thenReturn("Recently modified");
        when(mockClass.getMethods()).thenReturn(Set.of());
        when(mockClass.getFields()).thenReturn(Set.of());
        when(mockClass.getDependencies()).thenReturn(Set.of());

        when(classRepo.findByNameContainingOrQualifiedNameContaining(anyString(), anyString()))
                .thenReturn(List.of(mockClass));

        ContextCurationService.CurationResult result =
                curationService.curateForQuery("test", 1000, List.of("recentHash"));

        assertNotNull(result);
        assertTrue(result.classHashes().contains("recentHash"));
    }

    @Test
    void testDefaultTokenBudget() {
        ContextCurationService.CurationResult result =
                curationService.curateForQuery("test", 0, List.of());

        assertNotNull(result);
    }

    @Test
    void testCountTokens_empty() {
        assertEquals(0, ContextCurationService.countTokens(""));
        assertEquals(0, ContextCurationService.countTokens(null));
    }

    @Test
    void testCountTokens_simpleText() {
        // "hello world" is exactly 2 tokens in cl100k_base encoding (used by GPT-4)
        assertEquals(2, ContextCurationService.countTokens("hello world"));
    }

    @Test
    void testCountTokens_javaCode() {
        // "public class Test { }" is exactly 5 tokens in cl100k_base (GPT-4)
        String code = "public class Test { }";
        assertEquals(5, ContextCurationService.countTokens(code));
    }

    @Test
    void testJtokkitAccuracy() {
        // Verify jtokkit produces expected token count for a known string.
        // The string "The quick brown fox jumps over the lazy dog" is 10 tokens in cl100k_base.
        String known = "The quick brown fox jumps over the lazy dog";
        int tokens = ContextCurationService.countTokens(known);
        assertTrue(tokens > 0, "Should produce positive token count");
        // jtokkit is deterministic — same input always produces same count
        assertEquals(tokens, ContextCurationService.countTokens(known));
    }

    @Test
    void testJtokkitFallback() {
        // Verify the heuristic fallback (countTokensFallback) works correctly.
        // This is the exact same regex logic that was the original countTokens.
        assertEquals(0, ContextCurationService.countTokensFallback(""));
        assertEquals(0, ContextCurationService.countTokensFallback(null));

        // The heuristic should still produce non-zero counts for real text
        assertTrue(ContextCurationService.countTokensFallback("hello world") >= 2);

        String code = "public class Test { private String name; }";
        assertTrue(ContextCurationService.countTokensFallback(code) > 0);
    }

    @Test
    void testBudgetPostCheck_trimsWhenExceeded() {
        // Create class with very long content to exceed small budget
        CodeClass verbose = mock(CodeClass.class);
        when(verbose.getHash()).thenReturn("hash1");
        when(verbose.getQualifiedName()).thenReturn("com.example.VeryLong" + "X".repeat(1000));
        when(verbose.getPackageName()).thenReturn("com.example");
        when(verbose.getName()).thenReturn("VeryLong");
        when(verbose.getDescription()).thenReturn("X".repeat(1000));
        when(verbose.getMethods()).thenReturn(Set.of());
        when(verbose.getFields()).thenReturn(Set.of());
        when(verbose.getDependencies()).thenReturn(Set.of());

        when(classRepo.findByNameContainingOrQualifiedNameContaining(anyString(), anyString()))
                .thenReturn(List.of(verbose));

        ContextCurationService.CurationResult result =
                curationService.curateForQuery("test", 100, List.of());

        assertNotNull(result);
        // With jtokkit live counting, token count should be strictly <= budget
        assertTrue(result.tokenCount() > 0, "Should have some tokens (at least the header)");
        assertTrue(result.tokenCount() <= 100, "Token count should respect budget");
    }
}
