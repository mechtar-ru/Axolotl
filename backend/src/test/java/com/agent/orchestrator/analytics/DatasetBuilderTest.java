package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class DatasetBuilderTest {

    @Test
    void shouldAddTrajectory() {
        DatasetBuilder builder = new DatasetBuilder();
        TrajectoryExtractor.Trajectory trajectory = createTestTrajectory();

        builder.addTrajectory(trajectory);
        assertEquals(1, builder.getTrajectories().size());
    }

    @Test
    void shouldBuildExamplesFromTrajectories() {
        DatasetBuilder builder = new DatasetBuilder();
        builder.addTrajectory(createTestTrajectory());

        List<Map<String, String>> examples = builder.buildExamples();
        assertEquals(1, examples.size());
        assertTrue(examples.get(0).containsKey("prompt"));
        assertTrue(examples.get(0).containsKey("completion"));
    }

    @Test
    void shouldFilterDuplicateExamples() {
        DatasetBuilder builder = new DatasetBuilder();
        TrajectoryExtractor.Trajectory t1 = createTestTrajectory();
        TrajectoryExtractor.Trajectory t2 = createTestTrajectory(); // same trajectory

        builder.addTrajectory(t1);
        builder.addTrajectory(t2);
        builder.buildExamples();

        List<Map<String, String>> filtered = builder.filterExamples();
        assertEquals(1, filtered.size());
    }

    @Test
    void shouldApplyModelFormatting() {
        DatasetBuilder builder = new DatasetBuilder();
        builder.addTrajectory(createTestTrajectory());
        builder.buildExamples();

        List<Map<String, String>> formatted = builder.applyFormatting(DatasetBuilder.ModelType.LLM_TEXT);
        assertEquals(1, formatted.size());
        assertTrue(formatted.get(0).containsKey("text"));
    }

    @Test
    void shouldSplitDataset() {
        DatasetBuilder builder = new DatasetBuilder();
        for (int i = 0; i < 10; i++) {
            builder.addTrajectory(createTestTrajectory());
        }
        builder.buildExamples();

        DatasetBuilder.DatasetSplit split = builder.splitDataset(0.7, 0.2);
        assertEquals(7, split.getTrainSize());
        assertEquals(2, split.getValidationSize());
        assertEquals(1, split.getTestSize());
    }

    @Test
    void shouldGenerateJsonl() {
        DatasetBuilder builder = new DatasetBuilder();
        builder.addTrajectory(createTestTrajectory());
        builder.buildExamples();

        String jsonl = builder.getDatasetJsonl();
        assertTrue(jsonl.contains("prompt"));
        assertTrue(jsonl.contains("completion"));
        assertTrue(jsonl.endsWith("\n"));
    }

    @Test
    void shouldRejectInvalidTrajectory() {
        DatasetBuilder builder = new DatasetBuilder();
        builder.addTrajectory(null);
        assertEquals(0, builder.getTrajectories().size());
    }

    private TrajectoryExtractor.Trajectory createTestTrajectory() {
        List<TrajectoryExtractor.TrajectoryStep> steps = Arrays.asList(
            new TrajectoryExtractor.TrajectoryStep("node1", "source", "Source",
                Map.of("data", "test"), Map.of("result", "output"), 1000L, 1)
        );
        return new TrajectoryExtractor.Trajectory("traj1", "schema1", "Test",
            steps, 1000L, 2000L, true);
    }
}
