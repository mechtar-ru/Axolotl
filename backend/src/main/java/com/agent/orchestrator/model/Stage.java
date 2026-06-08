package com.agent.orchestrator.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class Stage {
    private String id;
    private String name;
    private String nodeType; // "source" | "review" | "agent" | "verifier" | "output" | "transform" | "custom"
    private String subagentSchemaId; // for "custom" type — delegate to another schema
    private String model;
    private List<String> fallbackModels;
    private String systemPrompt;
    private String userPrompt;
    private Map<String, Object> config;
    private List<String> dependencies; // stage IDs this stage depends on
    private Map<String, String> inputMapping; // source stage output field → this stage input field
    private Map<String, String> outputMapping; // this stage output field → target stage input field
    private String condition; // optional SpEL/JS condition for conditional execution
    private String loopCondition;
    private int maxIterations;
    private int maxRetries;
    private long timeoutMs;
    private boolean parallel; // if true, runs nodes within this stage in parallel
    private List<String> enabledTools;
    private String agentType;

    // UI layout
    private double positionX;
    private double positionY;
}
