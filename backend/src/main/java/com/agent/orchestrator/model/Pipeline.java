package com.agent.orchestrator.model;

import java.util.List;
import java.util.Map;

public class Pipeline {
    private String id;
    private String name;
    private String description;
    private List<Stage> stages;
    private Map<String, Object> config;
    private String parallelStrategy; // "sequential" | "parallel-stages" — how stages without deps run
    private int maxConcurrentStages;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<Stage> getStages() { return stages; }
    public void setStages(List<Stage> stages) { this.stages = stages; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    public String getParallelStrategy() { return parallelStrategy; }
    public void setParallelStrategy(String parallelStrategy) { this.parallelStrategy = parallelStrategy; }

    public int getMaxConcurrentStages() { return maxConcurrentStages; }
    public void setMaxConcurrentStages(int maxConcurrentStages) { this.maxConcurrentStages = maxConcurrentStages; }
}
