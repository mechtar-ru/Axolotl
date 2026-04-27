package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import java.util.*;

/**
 * Extracts trajectories from successful workflow executions.
 * A trajectory is a sequence of node executions with inputs, outputs, and state transitions.
 */


public class TrajectoryExtractor {

    /**
     * Represents a single step in a trajectory.
     */
    public static class TrajectoryStep {
        private final String nodeId;
        private final String nodeType;
        private final String nodeName;
        private final Map<String, Object> input;
        private final Map<String, Object> output;
        private final long timestamp;
        private final int stepNumber;

        public TrajectoryStep(String nodeId, String nodeType, String nodeName,
                             Map<String, Object> input, Map<String, Object> output,
                             long timestamp, int stepNumber) {
            this.nodeId = nodeId;
            this.nodeType = nodeType;
            this.nodeName = nodeName;
            this.input = input != null ? new HashMap<>(input) : new HashMap<>();
            this.output = output != null ? new HashMap<>(output) : new HashMap<>();
            this.timestamp = timestamp;
            this.stepNumber = stepNumber;
        }

        public String getNodeId() { return nodeId; }
        public String getNodeType() { return nodeType; }
        public String getNodeName() { return nodeName; }
        public Map<String, Object> getInput() { return new HashMap<>(input); }
        public Map<String, Object> getOutput() { return new HashMap<>(output); }
        public long getTimestamp() { return timestamp; }
        public int getStepNumber() { return stepNumber; }

        @Override
        public String toString() {
            return "Step{" + stepNumber + ": " + nodeType + "[" + nodeId + "]";
        }
    }

    /**
     * Represents a complete trajectory from a successful workflow execution.
     */
    public static class Trajectory {
        private final String trajectoryId;
        private final String schemaId;
        private final String schemaName;
        private final List<TrajectoryStep> steps;
        private final long startTime;
        private final long endTime;
        private final boolean successful;

        public Trajectory(String trajectoryId, String schemaId, String schemaName,
                         List<TrajectoryStep> steps, long startTime, long endTime, boolean successful) {
            this.trajectoryId = trajectoryId;
            this.schemaId = schemaId;
            this.schemaName = schemaName;
            this.steps = new ArrayList<>(steps);
            this.startTime = startTime;
            this.endTime = endTime;
            this.successful = successful;
        }

        public String getTrajectoryId() { return trajectoryId; }
        public String getSchemaId() { return schemaId; }
        public String getSchemaName() { return schemaName; }
        public List<TrajectoryStep> getSteps() { return new ArrayList<>(steps); }
        public long getStartTime() { return startTime; }
        public long getEndTime() { return endTime; }
        public boolean isSuccessful() { return successful; }

        /**
         * Converts trajectory to training example (prompt-completion pair).
         */
        public Map<String, String> toTrainingExample() {
            StringBuilder prompt = new StringBuilder();
            StringBuilder completion = new StringBuilder();

            for (TrajectoryStep step : steps) {
                prompt.append("Node[").append(step.getNodeType()).append("]: ");
                prompt.append(step.getInput()).append("\n");
                completion.append("Result: ").append(step.getOutput()).append("\n");
            }

            Map<String, String> example = new HashMap<>();
            example.put("prompt", prompt.toString().trim());
            example.put("completion", completion.toString().trim());
            return example;
        }

        @Override
        public String toString() {
            return "Trajectory{id=" + trajectoryId + ", steps=" + steps.size() + ", success=" + successful + "}";
        }
    }

    private final List<Trajectory> trajectories = new ArrayList<>();

    public TrajectoryExtractor() {}

    /**
     * Processes a successful workflow execution and extracts its trajectory.
     */
    public Trajectory extractTrajectory(WorkflowSchema schema, List<ExecutionRecord> executionRecords) {
        if (schema == null || executionRecords == null || executionRecords.isEmpty()) {
            return null;
        }

        // Sort records by timestamp
        List<ExecutionRecord> sorted = new ArrayList<>(executionRecords);
        sorted.sort(Comparator.comparingLong(ExecutionRecord::getTimestamp));

        // Check if execution was successful
        boolean successful = sorted.stream().allMatch(ExecutionRecord::isSuccess);
        if (!successful) {
            return null;
        }

        List<TrajectoryStep> steps = new ArrayList<>();
        Map<String, Node> nodeMap = new HashMap<>();
        if (schema.getNodes() != null) {
            for (Node node : schema.getNodes()) {
                nodeMap.put(node.getId(), node);
            }
        }

        for (int i = 0; i < sorted.size(); i++) {
            ExecutionRecord record = sorted.get(i);
            Node node = nodeMap.get(record.getNodeId());
            String nodeType = node != null ? node.getType() : "unknown";
            String nodeName = node != null ? node.getName() : record.getNodeId();

            TrajectoryStep step = new TrajectoryStep(
                record.getNodeId(),
                nodeType,
                nodeName,
                record.getInput(),
                record.getOutput(),
                record.getTimestamp(),
                i + 1
            );
            steps.add(step);
        }

        String trajectoryId = "traj_" + System.currentTimeMillis() + "_" + trajectories.size();
        Trajectory trajectory = new Trajectory(
            trajectoryId,
            schema.getId(),
            schema.getName(),
            steps,
            steps.isEmpty() ? System.currentTimeMillis() : steps.get(0).getTimestamp(),
            steps.isEmpty() ? System.currentTimeMillis() : steps.get(steps.size() - 1).getTimestamp(),
            true
        );

        trajectories.add(trajectory);
        return trajectory;
    }

    /**
     * Returns all extracted trajectories.
     */
    public List<Trajectory> getTrajectories() {
        return new ArrayList<>(trajectories);
    }

    /**
     * Returns only successful trajectories.
     */
    public List<Trajectory> getSuccessfulTrajectories() {
        return trajectories.stream().filter(Trajectory::isSuccessful).toList();
    }

    /**
     * Clears all stored trajectories.
     */
    public void clear() {
        trajectories.clear();
    }

    /**
     * Represents an execution record for a single node.
     */
    public static class ExecutionRecord {
        private final String nodeId;
        private final Map<String, Object> input;
        private final Map<String, Object> output;
        private final long timestamp;
        private final boolean success;

        public ExecutionRecord(String nodeId, Map<String, Object> input,
                              Map<String, Object> output, long timestamp, boolean success) {
            this.nodeId = nodeId;
            this.input = input != null ? new HashMap<>(input) : new HashMap<>();
            this.output = output != null ? new HashMap<>(output) : new HashMap<>();
            this.timestamp = timestamp;
            this.success = success;
        }

        public String getNodeId() { return nodeId; }
        public Map<String, Object> getInput() { return new HashMap<>(input); }
        public Map<String, Object> getOutput() { return new HashMap<>(output); }
        public long getTimestamp() { return timestamp; }
        public boolean isSuccess() { return success; }
    }
}
