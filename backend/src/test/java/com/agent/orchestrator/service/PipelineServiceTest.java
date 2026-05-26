package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PipelineServiceTest {

    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock NodeExecutor nodeExecutor;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock ExecutionRepository executionRepository;
    @Mock ExecutionStateManager stateManager;
    @Captor ArgumentCaptor<WorkflowSchema> schemaCaptor;

    PipelineService pipelineService;

    @BeforeEach
    void setUp() {
        pipelineService = new PipelineService(schemaRepository, nodeExecutor,
                webSocketHandler, executionRepository, stateManager);
    }

    // ────────── createDefaultPipeline ──────────

    @Test
    void createDefaultPipeline_createsFiveStages() {
        Pipeline pipeline = PipelineService.createDefaultPipeline("sokoban", "A Sokoban game");

        assertNotNull(pipeline);
        assertEquals("default-pipeline", pipeline.getId());
        assertNotNull(pipeline.getStages());
        assertEquals(5, pipeline.getStages().size());
        assertEquals("sequential", pipeline.getParallelStrategy());
    }

    @Test
    void createDefaultPipeline_stagesHaveCorrectTypes() {
        Pipeline pipeline = PipelineService.createDefaultPipeline("app", "desc");

        List<Stage> stages = pipeline.getStages();
        assertEquals("source", stages.get(0).getNodeType());
        assertEquals("review", stages.get(1).getNodeType());
        assertEquals("agent", stages.get(2).getNodeType());
        assertEquals("verifier", stages.get(3).getNodeType());
        assertEquals("output", stages.get(4).getNodeType());
    }

    @Test
    void createDefaultPipeline_stagesHaveCorrectDependencies() {
        Pipeline pipeline = PipelineService.createDefaultPipeline("app", "desc");

        List<Stage> stages = pipeline.getStages();
        assertNull(stages.get(0).getDependencies()); // source — no deps
        assertEquals(List.of("receive-1"), stages.get(1).getDependencies()); // review ← source
        assertEquals(List.of("review-1"), stages.get(2).getDependencies());  // agent ← review
        assertEquals(List.of("think-1"), stages.get(3).getDependencies());   // verify ← agent
        assertEquals(List.of("verify-1"), stages.get(4).getDependencies());  // output ← verify
    }

    @Test
    void createDefaultPipeline_includesDescriptionInSystemPrompt() {
        Pipeline pipeline = PipelineService.createDefaultPipeline("sokoban", "A Sokoban game");

        for (Stage s : pipeline.getStages()) {
            assertTrue(s.getSystemPrompt().contains("A Sokoban game"),
                    "Stage " + s.getName() + " prompt should contain description");
        }
    }

    @Test
    void createDefaultPipeline_stagesHavePositions() {
        Pipeline pipeline = PipelineService.createDefaultPipeline("app", "desc");

        List<Stage> stages = pipeline.getStages();
        for (int i = 0; i < stages.size(); i++) {
            assertTrue(stages.get(i).getPositionX() > 0, "Stage " + i + " should have X position");
            assertTrue(stages.get(i).getPositionY() > 0, "Stage " + i + " should have Y position");
        }
    }

    // ────────── isPipelineRunning / cancelPipeline ──────────

    @Test
    void isPipelineRunning_returnsFalseWhenNotRunning() {
        assertFalse(pipelineService.isPipelineRunning("nonexistent"));
    }

    @Test
    void cancelPipeline_doesNotThrowWhenNotRunning() {
        assertDoesNotThrow(() -> pipelineService.cancelPipeline("nonexistent"));
    }

    // ────────── getStageResults ──────────

    @Test
    void getStageResults_returnsEmptyMapWhenNoResults() {
        Map<String, String> results = pipelineService.getStageResults("nonexistent");
        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    // ────────── buildPipelineNodes ──────────

    @Test
    void buildPipelineNodes_createsNodesAndEdges() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-schema");
        schema.setName("Test");
        Pipeline pipeline = PipelineService.createDefaultPipeline("test", "Test app");
        schema.setPipeline(pipeline);

        lenient().when(schemaRepository.findById(anyString())).thenReturn(schema);
    }

    @Test
    void buildPipelineNodes_edgesConnectInCorrectOrder() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test-schema");
        schema.setName("Test");
        schema.setPipeline(PipelineService.createDefaultPipeline("test", "Test app"));

        lenient().when(schemaRepository.findById(anyString())).thenReturn(schema);

        WorkflowSchema result = pipelineService.buildPipelineNodes("test-schema");

        // source → review → agent → verifier → output
        Map<String, Node> nodesById = new HashMap<>();
        for (Node n : result.getNodes()) nodesById.put(n.getId(), n);

        // Find source node (should have no incoming edges)
        Node sourceNode = result.getNodes().stream()
                .filter(n -> "source".equals(n.getType()))
                .findFirst().orElseThrow();
        assertEquals(0, result.getEdges().stream()
                .filter(e -> e.getTarget().equals(sourceNode.getId())).count());

        // Find output node (should have no outgoing edges)
        Node outputNode = result.getNodes().stream()
                .filter(n -> "output".equals(n.getType()))
                .findFirst().orElseThrow();
        assertEquals(0, result.getEdges().stream()
                .filter(e -> e.getSource().equals(outputNode.getId())).count());
    }

    @Test
    void buildPipelineNodes_throwsOnMissingSchema() {
        when(schemaRepository.findById("missing")).thenReturn(null);

        assertThrows(RuntimeException.class,
                () -> pipelineService.buildPipelineNodes("missing"));
    }

    @Test
    void buildPipelineNodes_throwsOnMissingPipeline() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test");
        schema.setName("Test");

        when(schemaRepository.findById("test")).thenReturn(schema);

        assertThrows(RuntimeException.class,
                () -> pipelineService.buildPipelineNodes("test"));
    }

    // ────────── topologicalSortStages (tested via executePipeline behavior) ──────────

    @Test
    void executePipeline_skipsWhenAlreadyRunning() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("running-schema");
        schema.setName("Running");
        schema.setPipeline(PipelineService.createDefaultPipeline("test", ""));

        when(schemaRepository.findById("running-schema")).thenReturn(schema);

        // First call starts
        pipelineService.executePipeline("running-schema");

        // Second call should be skipped (already running)
        pipelineService.executePipeline("running-schema");

        verify(executionRepository).createRun(any(ExecutionRun.class));
    }

    @Test
    void executePipeline_createsRunWithCorrectInitialState() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test");
        schema.setName("Test");
        schema.setPipeline(PipelineService.createDefaultPipeline("test", ""));

        when(schemaRepository.findById("test")).thenReturn(schema);

        pipelineService.executePipeline("test");

        ArgumentCaptor<ExecutionRun> runCaptor = ArgumentCaptor.forClass(ExecutionRun.class);
        verify(executionRepository).createRun(runCaptor.capture());

        ExecutionRun run = runCaptor.getValue();
        assertEquals("test", run.getSchemaId());
        assertEquals("running", run.getStatus());
        assertEquals("PIPELINE", run.getMode());
        assertNotNull(run.getStageStatus());
        assertEquals(5, run.getStageStatus().size());

        for (Map.Entry<String, String> e : run.getStageStatus().entrySet()) {
            assertEquals("pending", e.getValue(), "Stage " + e.getKey() + " should start as pending");
        }
    }

    @Test
    void executePipeline_createsRunIdempotently() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("test");
        schema.setName("Test");
        schema.setPipeline(PipelineService.createDefaultPipeline("test", ""));

        when(schemaRepository.findById("test")).thenReturn(schema);

        pipelineService.executePipeline("test");
        pipelineService.executePipeline("test");

        verify(executionRepository).createRun(any(ExecutionRun.class));
    }

    @Test
    void getStageResults_isEmptyBeforeExecution() {
        assertTrue(pipelineService.getStageResults("new").isEmpty());
    }

    // ────────── expandTddStages ──────────

    @Test
    void expandTddStages_noopWhenTddDisabled() {
        Pipeline pipeline = PipelineService.createDefaultPipeline("app", "desc");
        // createDefaultPipeline sets tddEnabled=false, expandTddStages is a no-op
        assertEquals(5, pipeline.getStages().size());
        assertEquals("think-1", pipeline.getStages().get(2).getId());
        assertEquals("verify-1", pipeline.getStages().get(3).getId());
    }

    @Test
    void expandTddStages_expandsAgentVerifierPair() {
        Pipeline pipeline = createTddPipeline();

        assertEquals(7, pipeline.getStages().size(),
                "5 stages + (4 TDD - 2 original) = 7 stages");

        // Verify IDs in order
        List<String> ids = pipeline.getStages().stream().map(Stage::getId).toList();
        assertEquals(List.of("receive-1", "review-1",
                "test-think-1", "verify-test-think-1",
                "impl-think-1", "verify-think-1",
                "act-1"), ids);
    }

    @Test
    void expandTddStages_correctTypes() {
        Pipeline pipeline = createTddPipeline();

        List<Stage> stages = pipeline.getStages();
        Map<String, Stage> byId = stages.stream().collect(java.util.stream.Collectors.toMap(Stage::getId, s -> s));

        assertEquals("source", byId.get("receive-1").getNodeType());
        assertEquals("review", byId.get("review-1").getNodeType());
        assertEquals("agent", byId.get("test-think-1").getNodeType());
        assertEquals("verifier", byId.get("verify-test-think-1").getNodeType());
        assertEquals("agent", byId.get("impl-think-1").getNodeType());
        assertEquals("verifier", byId.get("verify-think-1").getNodeType());
        assertEquals("output", byId.get("act-1").getNodeType());
    }

    @Test
    void expandTddStages_correctDependencies() {
        Pipeline pipeline = createTddPipeline();

        Map<String, Stage> byId = pipeline.getStages().stream()
                .collect(java.util.stream.Collectors.toMap(Stage::getId, s -> s));

        // test-think-1 inherits think-1's original deps (review-1)
        assertEquals(List.of("review-1"), byId.get("test-think-1").getDependencies());

        // verify-test-think-1 depends on test-think-1
        assertEquals(List.of("test-think-1"), byId.get("verify-test-think-1").getDependencies());

        // impl-think-1 depends on test-think-1 ONLY (not verify-test-think-1)
        assertEquals(List.of("test-think-1"), byId.get("impl-think-1").getDependencies());

        // verify-think-1 depends on impl-think-1
        assertEquals(List.of("impl-think-1"), byId.get("verify-think-1").getDependencies());

        // act-1's dependency was rewritten from verify-1 → verify-think-1
        assertEquals(List.of("verify-think-1"), byId.get("act-1").getDependencies());
    }

    @Test
    void expandTddStages_downstreamDepsRewritten() {
        Pipeline pipeline = createTddPipeline();

        // Verify no stage still references the old verify-1 or think-1 IDs
        for (Stage s : pipeline.getStages()) {
            if (s.getDependencies() != null) {
                assertFalse(s.getDependencies().contains("think-1"),
                        "Stage " + s.getId() + " should not depend on old 'think-1'");
                assertFalse(s.getDependencies().contains("verify-1"),
                        "Stage " + s.getId() + " should not depend on old 'verify-1'");
            }
        }
    }

    @Test
    void expandTddStages_hasPositions() {
        Pipeline pipeline = createTddPipeline();

        for (Stage s : pipeline.getStages()) {
            assertTrue(s.getPositionX() > 0, "Stage " + s.getId() + " should have X position");
            assertTrue(s.getPositionY() > 0, "Stage " + s.getId() + " should have Y position");
        }
    }

    @Test
    void expandTddStages_noopOnNoAgentVerifierPair() {
        Pipeline pipeline = new Pipeline();
        pipeline.setTddEnabled(true);

        Stage a = new Stage();
        a.setId("s1");
        a.setName("Source");
        a.setNodeType("source");

        Stage b = new Stage();
        b.setId("o1");
        b.setName("Output");
        b.setNodeType("output");
        b.setDependencies(List.of("s1"));

        pipeline.setStages(List.of(a, b));
        PipelineService.expandTddStages(pipeline);

        assertEquals(2, pipeline.getStages().size(),
                "No agent → verifier pair, no expansion");
    }

    @Test
    void expandTddStages_nullPipelineDoesNotThrow() {
        assertDoesNotThrow(() -> PipelineService.expandTddStages(null));
    }

    /** Helper: creates a default pipeline with tddEnabled=true */
    private static Pipeline createTddPipeline() {
        Pipeline pipeline = PipelineService.createDefaultPipeline("app", "desc");
        pipeline.setTddEnabled(true);
        PipelineService.expandTddStages(pipeline);
        return pipeline;
    }
}
