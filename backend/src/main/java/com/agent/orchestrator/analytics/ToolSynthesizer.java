package com.agent.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Synthesizes new tools from frequently used command sequences.
 * 
 * Integration Points:
 * - ToolChainingOptimizer: For learning optimal tool sequences
 * - SandboxedExecutor: For testing synthesized tools
 * - NodeFactory: For creating nodes from synthesized tools
 * 
 * Difference from NodeFactory:
 * - NodeFactory: Creates workflow nodes from detected patterns
 * - ToolSynthesizer: Creates executable tools from tool invocation sequences
 */

public class ToolSynthesizer {
    private static final Logger logger = LoggerFactory.getLogger(ToolSynthesizer.class);
    
    private final Map<String, SynthesizedTool> synthesizedTools = new HashMap<>();
    private int minSequenceFrequency = 3;
    
    public ToolSynthesizer() {}
    
    public void processToolHistory(List<ToolInvocation> invocations) {
        logger.info("Processing {} tool invocations", invocations.size());
        Map<String, ToolSequence> sequences = extractSequences(invocations);
        identifyFrequentPatterns(sequences);
    }
    
    public Map<String, ToolSequence> extractSequences(List<ToolInvocation> invocations) {
        Map<String, ToolSequence> sequences = new HashMap<>();
        List<ToolInvocation> currentSequence = new ArrayList<>();
        
        for (ToolInvocation inv : invocations) {
            if (inv.getSessionId() != null && inv.getSessionId().equals(getLastSession(currentSequence))) {
                currentSequence.add(inv);
            } else {
                addSequence(sequences, currentSequence);
                currentSequence = new ArrayList<>();
                currentSequence.add(inv);
            }
        }
        addSequence(sequences, currentSequence);
        return sequences;
    }
    
    private String getLastSession(List<ToolInvocation> sequence) {
        return sequence.isEmpty() ? null : sequence.get(sequence.size() - 1).getSessionId();
    }
    
    private void addSequence(Map<String, ToolSequence> sequences, List<ToolInvocation> current) {
        if (current.size() >= 2) {
            String key = generateSequenceKey(current);
            sequences.computeIfAbsent(key, k -> new ToolSequence(current))
                     .incrementFrequency();
        }
    }
    
    private String generateSequenceKey(List<ToolInvocation> sequence) {
        StringBuilder key = new StringBuilder();
        for (ToolInvocation inv : sequence) {
            key.append(inv.getToolName()).append("->");
        }
        return key.toString();
    }
    
    public void identifyFrequentPatterns(Map<String, ToolSequence> sequences) {
        for (ToolSequence seq : sequences.values()) {
            if (seq.getFrequency() >= minSequenceFrequency) {
                logger.info("Found frequent pattern: {} (freq: {})", 
                    seq.getToolNames(), seq.getFrequency());
            }
        }
    }
    
    public SynthesizedTool synthesizeFromSequence(ToolSequence sequence) {
        String toolName = generateToolName(sequence.getToolNames());
        String inputSpec = inferInputSpec(sequence);
        String outputSpec = inferOutputSpec(sequence);
        String implementation = generateImplementation(sequence);
        
        SynthesizedTool tool = new SynthesizedTool(
            toolName,
            sequence.getToolNames(),
            inputSpec,
            outputSpec,
            implementation
        );
        
        synthesizedTools.put(toolName, tool);
        logger.info("Synthesized tool: {}", toolName);
        return tool;
    }
    
    private String generateToolName(List<String> toolNames) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < Math.min(toolNames.size(), 3); i++) {
            if (i > 0) name.append("-");
            name.append(toolNames.get(i));
        }
        return name.toString();
    }
    
    private String inferInputSpec(ToolSequence sequence) {
        Set<String> inputTypes = new HashSet<>();
        for (ToolInvocation inv : sequence.getInvocations()) {
            if (inv.getInputType() != null) {
                inputTypes.add(inv.getInputType());
            }
        }
        return String.join(", ", inputTypes);
    }
    
    private String inferOutputSpec(ToolSequence sequence) {
        if (sequence.getInvocations().isEmpty()) {
            return "void";
        }
        ToolInvocation last = sequence.getInvocations().get(sequence.getInvocations().size() - 1);
        return last.getOutputType() != null ? last.getOutputType() : "unknown";
    }
    
    private String generateImplementation(ToolSequence sequence) {
        StringBuilder impl = new StringBuilder();
        impl.append("#!/bin/bash\n");
        impl.append("# Synthesized from: ").append(sequence.getToolNames()).append("\n");
        impl.append("# Frequency: ").append(sequence.getFrequency()).append("\n\n");
        
        for (ToolInvocation inv : sequence.getInvocations()) {
            impl.append(inv.getToolName());
            if (inv.getArgs() != null && !inv.getArgs().isEmpty()) {
                impl.append(" ");
                impl.append(String.join(" ", inv.getArgs()));
            }
            impl.append("\n");
        }
        
        return impl.toString();
    }
    
    public SynthesizedTool getTool(String name) {
        return synthesizedTools.get(name);
    }
    
    public Map<String, SynthesizedTool> getAllTools() {
        return new HashMap<>(synthesizedTools);
    }
    
    public int getMinSequenceFrequency() { return minSequenceFrequency; }
    public void setMinSequenceFrequency(int freq) { this.minSequenceFrequency = freq; }
    
    public static class ToolInvocation {
        private final String toolName;
        private final List<String> args;
        private final String sessionId;
        private final String inputType;
        private final String outputType;
        private final long timestamp;
        
        public ToolInvocation(String toolName, List<String> args, String sessionId) {
            this.toolName = toolName;
            this.args = args;
            this.sessionId = sessionId;
            this.inputType = null;
            this.outputType = null;
            this.timestamp = System.currentTimeMillis();
        }
        
        public ToolInvocation(String toolName, List<String> args, String sessionId, 
                            String inputType, String outputType) {
            this.toolName = toolName;
            this.args = args;
            this.sessionId = sessionId;
            this.inputType = inputType;
            this.outputType = outputType;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getToolName() { return toolName; }
        public List<String> getArgs() { return args; }
        public String getSessionId() { return sessionId; }
        public String getInputType() { return inputType; }
        public String getOutputType() { return outputType; }
        public long getTimestamp() { return timestamp; }
    }
    
    public static class ToolSequence {
        private final List<ToolInvocation> invocations;
        private int frequency;
        
        public ToolSequence(List<ToolInvocation> invocations) {
            this.invocations = new ArrayList<>(invocations);
            this.frequency = 1;
        }
        
        public List<String> getToolNames() {
            List<String> names = new ArrayList<>();
            for (ToolInvocation inv : invocations) {
                names.add(inv.getToolName());
            }
            return names;
        }
        
        public List<ToolInvocation> getInvocations() { return new ArrayList<>(invocations); }
        public int getFrequency() { return frequency; }
        public void incrementFrequency() { frequency++; }
    }
    
    public static class SynthesizedTool {
        private final String name;
        private final List<String> sourceSequence;
        private final String inputSpec;
        private final String outputSpec;
        private final String implementation;
        private final long createdAt;
        
        public SynthesizedTool(String name, List<String> sourceSequence, 
                            String inputSpec, String outputSpec, String implementation) {
            this.name = name;
            this.sourceSequence = new ArrayList<>(sourceSequence);
            this.inputSpec = inputSpec;
            this.outputSpec = outputSpec;
            this.implementation = implementation;
            this.createdAt = System.currentTimeMillis();
        }
        
        public String getName() { return name; }
        public List<String> getSourceSequence() { return new ArrayList<>(sourceSequence); }
        public String getInputSpec() { return inputSpec; }
        public String getOutputSpec() { return outputSpec; }
        public String getImplementation() { return implementation; }
        public long getCreatedAt() { return createdAt; }
        
        @Override
        public String toString() {
            return "SynthesizedTool{name=" + name + ", sources=" + sourceSequence + "}";
        }
    }
}