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

        when(classRepo.findByNameContainingOrQualifiedNameContaining("test", "test"))
                .thenReturn(List.of(mockClass));

        ContextCurationService.CurationResult result =
                curationService.curateForQuery("test", 500, List.of());

        assertNotNull(result);
        assertTrue(result.tokenCount() <= 500, "Should respect token budget");
        assertTrue(!result.classHashes().isEmpty() || !result.methodHashes().isEmpty(),
                "Should return some hashes");
        assertEquals("hybrid_relevance_centrality", result.strategy());
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
}