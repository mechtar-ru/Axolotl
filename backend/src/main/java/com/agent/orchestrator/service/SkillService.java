package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Skill;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final MemPalaceClient memPalaceClient;
    private final LlmService llmService;
    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final Map<String, List<ToolCallInfo>> trajectories = new ConcurrentHashMap<>();

    public SkillService(MemPalaceClient memPalaceClient, LlmService llmService) {
        this.memPalaceClient = memPalaceClient;
        this.llmService = llmService;
    }

    public void recordToolCall(String trajectoryId, String toolName, 
            Map<String, Object> args, String result, boolean success, long durationMs) {
        ToolCallInfo info = new ToolCallInfo(toolName);
        info.setArguments(args);
        info.setResult(result);
        info.setSuccess(success);
        info.setDurationMs(durationMs);

        trajectories.computeIfAbsent(trajectoryId, k -> new ArrayList<>()).add(info);
    }

    public void finishTrajectory(String trajectoryId, String taskDescription, boolean success) {
        List<ToolCallInfo> calls = trajectories.remove(trajectoryId);
        if (calls == null || calls.isEmpty()) return;

        extractPatternsFromCalls(calls, taskDescription, success);

        if (calls.size() >= 2) {
            generateSkill(calls, taskDescription);
        }
    }

    private void extractPatternsFromCalls(List<ToolCallInfo> calls, String taskDescription, boolean success) {
        if (!memPalaceClient.isEnabled()) return;

        String toolSequence = calls.stream()
                .map(ToolCallInfo::getToolName)
                .collect(Collectors.joining(" -> "));

        String memoryEntry = String.format(
            "Task: %s | Pattern: %s | Success: %s | Time: %s",
            taskDescription, toolSequence, success, Instant.now());

        memPalaceClient.addDrawer("axolotl", "patterns", memoryEntry, "trajectory:" + taskDescription);
    }

    private void generateSkill(List<ToolCallInfo> calls, String taskDescription) {
        String toolSequence = calls.stream()
                .map(ToolCallInfo::getToolName)
                .collect(Collectors.joining(", "));

        String skillId = "auto_" + UUID.randomUUID().toString().substring(0, 8);
        String skillName = "Auto" + generateNameFromTools(calls);

        String promptTemplate = String.format("""
            Use this workflow to accomplish: %s
            
            Tool sequence:
            %s
            
            Guidelines:
            - Execute tools in sequence shown above
            - Use tool outputs as inputs for next tool
            - Report each step result
            """, taskDescription, toolSequence);

        Skill skill = new Skill(skillName, 
                "Auto-generated skill for: " + taskDescription,
                promptTemplate, 
                toolSequence);
        skill.setCategory("auto-generated");

        saveSkill(skill);

        if (memPalaceClient.isEnabled()) {
            memPalaceClient.addDrawer("axolotl", "skills",
                    String.format("Generated: %s using [%s]", skillName, toolSequence),
                    "skill:" + skillId);
        }

        log.info("Auto-generated skill: {} with tools [{}]", skillName, toolSequence);
    }

    private String generateNameFromTools(List<ToolCallInfo> calls) {
        if (calls.isEmpty()) return "Empty";
        
        Set<String> uniqueTools = calls.stream()
                .map(ToolCallInfo::getToolName)
                .collect(Collectors.toSet());

        StringJoiner joiner = new StringJoiner("And");
        for (String tool : uniqueTools) {
            String name = tool.replace("_", " ");
            joiner.add(Character.toUpperCase(name.charAt(0)) + name.substring(1));
        }
        return joiner.toString();
    }

    public Skill getSkill(String id) {
        return skills.get(id);
    }

    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }

    public List<Skill> getEnabledSkills() {
        return skills.values().stream()
                .filter(Skill::isEnabled)
                .sorted((a, b) -> Double.compare(b.getSuccessRate(), a.getSuccessRate()))
                .collect(Collectors.toList());
    }

    public void saveSkill(Skill skill) {
        skills.put(skill.getId(), skill);
    }

    public Skill addSkill(Skill skill) {
        skills.put(skill.getId(), skill);
        return skill;
    }

    public Skill updateSkill(String id, Skill skill) {
        skill.setId(id);
        skills.put(id, skill);
        return skill;
    }

    public void deleteSkill(String id) {
        skills.remove(id);
    }

    public void recordUsage(String skillId, boolean success) {
        Skill skill = skills.get(skillId);
        if (skill != null) {
            skill.incrementUsage();
            // Update success rate based on rolling average
            double newRate = success ? 1.0 : 0.0;
            if (skill.getUsageCount() > 1) {
                double oldRate = skill.getSuccessRate();
                int oldCount = skill.getUsageCount() - 1;
                newRate = ((oldRate * oldCount) + (success ? 1.0 : 0.0)) / skill.getUsageCount();
            }
            skill.setSuccessRate(newRate);
            skill.setLastUsedAt(Instant.now());
        }
    }

    public List<Skill> findMatchingSkills(String query) {
        String[] queryTools = query.toLowerCase().split("[,\\s]+");
        
        return skills.values().stream()
                .filter(s -> s.getTriggerPattern() != null && 
                           s.getTriggerPattern().toLowerCase().contains(query.toLowerCase()))
                .sorted((a, b) -> Double.compare(b.getSuccessRate(), a.getSuccessRate()))
                .collect(Collectors.toList());
    }

    public List<Skill> findSimilarSkillsByTools(List<String> requiredTools) {
        return skills.values().stream()
                .filter(s -> {
                    if (s.getTriggerPattern() == null) return false;
                    String trigger = s.getTriggerPattern();
                    return requiredTools.stream().anyMatch(t -> trigger.contains(t));
                })
                .sorted((a, b) -> Double.compare(b.getSuccessRate(), a.getSuccessRate()))
                .collect(Collectors.toList());
    }

    /**
     * Search agentskills.io registry.
     */
    public List<Map<String, Object>> searchInRegistry(String query) {
        log.info("Searching agentskills.io registry for: {}", query);
        // Stub - in real implementation, call agentskills.io API
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> skill1 = new HashMap<>();
        skill1.put("name", "example-skill");
        skill1.put("description", "Example skill from registry");
        skill1.put("version", "1.0.0");
        skill1.put("repository", "https://github.com/user/example-skill");
        results.add(skill1);
        return results;
    }

    /**
     * Validate skill spec compliance.
     */
    public Map<String, Object> validateSpecCompliance(Skill skill) {
        log.info("Validating skill spec compliance for: {}", skill.getName());
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();

        if (skill.getName() == null || skill.getName().isBlank()) {
            errors.add("Missing required field: name");
        }
        if (skill.getDescription() == null || skill.getDescription().isBlank()) {
            errors.add("Missing required field: description");
        }
        if (skill.getVersion() == null || skill.getVersion().isBlank()) {
            errors.add("Missing recommended field: version");
        }
        if (skill.getRepository() == null || skill.getRepository().isBlank()) {
            errors.add("Missing recommended field: repository");
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("skill", Map.of(
            "name", skill.getName(),
            "version", skill.getVersion(),
            "authors", skill.getAuthors(),
            "tags", skill.getTags(),
            "platforms", skill.getPlatforms(),
            "repository", skill.getRepository(),
            "docs", skill.getDocs()
        ));
        return result;
    }

    /**
     * Sync skills from registry.
     */
    public Map<String, Object> syncFromRegistry() {
        log.info("Syncing skills from agentskills.io registry...");
        // Stub - in real implementation, fetch from registry and update local skills
        return Map.of(
            "status", "synced",
            "count", 0,
            "message", "Stub: would sync from agentskills.io registry"
        );
    }

    /**
     * Discover available skills from registry.
     */
    public List<Map<String, Object>> discoverFromRegistry() {
        log.info("Discovering skills from agentskills.io registry...");
        // Stub - in real implementation, list available skills
        return searchInRegistry("");
    }

    public static class ToolCallInfo {
        private String toolName;
        private Map<String, Object> arguments = new HashMap<>();
        private String result;
        private boolean success;
        private long durationMs;

        public ToolCallInfo(String toolName) {
            this.toolName = toolName;
        }

        public String getToolName() { return toolName; }
        public Map<String, Object> getArguments() { return arguments; }
        public String getResult() { return result; }
        public boolean isSuccess() { return success; }
        public long getDurationMs() { return durationMs; }

        public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }
        public void setResult(String result) { this.result = result; }
        public void setSuccess(boolean success) { this.success = success; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    }
}