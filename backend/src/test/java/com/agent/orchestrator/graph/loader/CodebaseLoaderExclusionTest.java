package com.agent.orchestrator.graph.loader;

import com.agent.orchestrator.graph.repository.CodeClassRepository;
import com.agent.orchestrator.graph.repository.CodeMethodRepository;
import com.agent.orchestrator.graph.repository.CodePackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CodebaseLoaderExclusionTest {

    @Mock
    private CodePackageRepository packageRepo;
    @Mock
    private CodeClassRepository classRepo;
    @Mock
    private CodeMethodRepository methodRepo;

    private CodebaseLoader loader;

    @BeforeEach
    void setUp() {
        loader = new CodebaseLoader(packageRepo, classRepo, methodRepo);
    }

    @Test
    void testExclusionListExists() {
        // Verify the exclusion list is non-empty and contains expected packages
        // This tests that the constant was properly defined
        assertNotNull(loader.getExcludedPackages());
        assertFalse(loader.getExcludedPackages().isEmpty());
        assertTrue(loader.getExcludedPackages().contains("com.agent.orchestrator.graph"));
    }

    @Test
    void testExcludedPathDetection() {
        assertTrue(loader.isExcluded(Path.of("/project/backend/src/main/java/com/agent/orchestrator/graph/loader/Loader.java")));
        assertTrue(loader.isExcluded(Path.of("/project/backend/src/main/java/com/agent/orchestrator/config/SecurityConfig.java")));
        assertFalse(loader.isExcluded(Path.of("/project/backend/src/main/java/com/agent/orchestrator/service/MyService.java")));
        assertFalse(loader.isExcluded(Path.of("/project/frontend/src/App.vue")));
    }
}
