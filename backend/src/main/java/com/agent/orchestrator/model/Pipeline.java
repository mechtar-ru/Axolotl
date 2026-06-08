package com.agent.orchestrator.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class Pipeline {
    private String id;
    private String name;
    private String description;
    private List<Stage> stages;
    private Map<String, Object> config;
    private String parallelStrategy; // "sequential" | "parallel-stages" — how stages without deps run
    private int maxConcurrentStages;
    /** When true, each branch expands to 4 stages: test → verify-test → impl → verify (TDD mode). */
    private boolean tddEnabled;
}
