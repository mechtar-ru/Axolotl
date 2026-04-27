package com.agent.orchestrator.analytics;

import java.util.*;

/**
 * Builds training datasets from extracted trajectories.
 * Converts trajectories into formatted training examples for fine-tuning.
 */


public class DatasetBuilder {

    public enum ModelType {
        LLM_TEXT,
        CHAT_FORMAT,
        INSTRUCTION_FORMAT
    }

    private final List<TrajectoryExtractor.Trajectory> trajectories = new ArrayList<>();
    private final List<Map<String, String>> examples = new ArrayList<>();
    private double minSuccessRate = 1.0;

    public DatasetBuilder() {}

    public void setMinSuccessRate(double rate) {
        this.minSuccessRate = rate;
    }

    /**
     * Adds a trajectory to the dataset.
     */
    public void addTrajectory(TrajectoryExtractor.Trajectory trajectory) {
        if (trajectory != null && trajectory.isSuccessful()) {
            trajectories.add(trajectory);
        }
    }

    /**
     * Adds multiple trajectories to the dataset.
     */
    public void addTrajectories(List<TrajectoryExtractor.Trajectory> trajs) {
        if (trajs != null) {
            for (TrajectoryExtractor.Trajectory t : trajs) {
                addTrajectory(t);
            }
        }
    }

    /**
     * Converts trajectories to training examples.
     */
    public List<Map<String, String>> buildExamples() {
        examples.clear();
        for (TrajectoryExtractor.Trajectory trajectory : trajectories) {
            Map<String, String> example = trajectory.toTrainingExample();
            if (isValidExample(example)) {
                examples.add(example);
            }
        }
        return new ArrayList<>(examples);
    }

    private boolean isValidExample(Map<String, String> example) {
        return example != null
            && example.containsKey("prompt")
            && example.containsKey("completion")
            && !example.get("prompt").isEmpty()
            && !example.get("completion").isEmpty();
    }

    /**
     * Applies model-specific formatting to examples.
     */
    public List<Map<String, String>> applyFormatting(ModelType modelType) {
        List<Map<String, String>> formatted = new ArrayList<>();
        for (Map<String, String> example : examples) {
            Map<String, String> formattedExample = new HashMap<>();
            switch (modelType) {
                case LLM_TEXT -> {
                    formattedExample.put("text", example.get("prompt") + "\n" + example.get("completion"));
                }
                case CHAT_FORMAT -> {
                    formattedExample.put("messages", "[{\"role\":\"user\",\"content\":\"" + example.get("prompt") + "\"},"
                        + "{\"role\":\"assistant\",\"content\":\"" + example.get("completion") + "\"}]");
                }
                case INSTRUCTION_FORMAT -> {
                    formattedExample.put("instruction", example.get("prompt"));
                    formattedExample.put("response", example.get("completion"));
                }
            }
            formatted.add(formattedExample);
        }
        return formatted;
    }

    /**
     * Filters out low-quality or duplicate examples.
     */
    public List<Map<String, String>> filterExamples() {
        Set<String> seen = new HashSet<>();
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> example : examples) {
            String key = example.get("prompt").hashCode() + "_" + example.get("completion").hashCode();
            if (!seen.contains(key) && hasMinimumQuality(example)) {
                seen.add(key);
                filtered.add(example);
            }
        }
        return filtered;
    }

    private boolean hasMinimumQuality(Map<String, String> example) {
        String prompt = example.get("prompt");
        String completion = example.get("completion");
        return prompt != null && completion != null
            && prompt.length() > 10 && completion.length() > 5;
    }

    /**
     * Splits dataset into train/val/test sets.
     */
    public DatasetSplit splitDataset(double trainRatio, double valRatio) {
        int total = examples.size();
        if (total == 0) {
            return new DatasetSplit(List.of(), List.of(), List.of());
        }

        int trainEnd = (int) (total * trainRatio);
        int valEnd = (int) (total * (trainRatio + valRatio));

        List<Map<String, String>> train = examples.subList(0, trainEnd);
        List<Map<String, String>> val = examples.subList(trainEnd, Math.min(valEnd, total));
        List<Map<String, String>> test = examples.subList(Math.min(valEnd, total), total);

        return new DatasetSplit(new ArrayList<>(train), new ArrayList<>(val), new ArrayList<>(test));
    }

    /**
     * Gets the dataset in JSONL format.
     */
    public String getDatasetJsonl() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> example : examples) {
            sb.append("{\"prompt\":\"").append(escapeJson(example.get("prompt")))
               .append("\",\"completion\":\"").append(escapeJson(example.get("completion")))
               .append("\"}\n");
        }
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public List<Map<String, String>> getExamples() { return new ArrayList<>(examples); }
    public List<TrajectoryExtractor.Trajectory> getTrajectories() { return new ArrayList<>(trajectories); }

    public void clear() {
        trajectories.clear();
        examples.clear();
    }

    /**
     * Represents a dataset split.
     */
    public static class DatasetSplit {
        private final List<Map<String, String>> train;
        private final List<Map<String, String>> validation;
        private final List<Map<String, String>> test;

        public DatasetSplit(List<Map<String, String>> train, List<Map<String, String>> validation, List<Map<String, String>> test) {
            this.train = train;
            this.validation = validation;
            this.test = test;
        }

        public List<Map<String, String>> getTrain() { return train; }
        public List<Map<String, String>> getValidation() { return validation; }
        public List<Map<String, String>> getTest() { return test; }

        public int getTrainSize() { return train.size(); }
        public int getValidationSize() { return validation.size(); }
        public int getTestSize() { return test.size(); }
    }
}
