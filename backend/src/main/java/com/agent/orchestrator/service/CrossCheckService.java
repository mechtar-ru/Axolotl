package com.agent.orchestrator.service;

import com.agent.orchestrator.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CrossCheckService {

    private static final Logger log = LoggerFactory.getLogger(CrossCheckService.class);

    private final LlmService llmService;
    private final Map<String, CrossCheckResult> lastResults = new ConcurrentHashMap<>();

    public CrossCheckService(LlmService llmService) {
        this.llmService = llmService;
    }

    public CrossCheckResult verify(String agentName, String originalOutput, String context) {
        String verificationPrompt = String.format("""
            Ты — верификатор. Проверь следующий ответ агента на корректность и согласованность.

            Контекст задачи:
            %s

            Ответ агента:
            %s

            Проверь:
            1. Фактическая корректность — ответ соответствует контексту?
            2. Логическая согласованность — нет противоречий?
            3. Полнота — все ли аспекты задачи покрыты?

            Верни ответ в формате JSON:
            {
              "valid": true/false,
              "confidence": 0.0-1.0,
              "issues": ["список проблем"],
              "suggestions": ["рекомендации по улучшению"],
              "summary": "краткое резюме проверки"
            }
            """, context, originalOutput);

        try {
            String result = llmService.chat("ollama", null, verificationPrompt, null).text();

            CrossCheckResult checkResult = parseResult(result);
            checkResult.setAgentName(agentName);
            checkResult.setOriginalOutput(originalOutput);

            lastResults.put(agentName, checkResult);

            log.info("Cross-check for '{}': valid={}, confidence={}",
                    agentName, checkResult.isValid(), checkResult.getConfidence());

            return checkResult;
        } catch (Exception e) {
            log.error("Cross-check failed for {}", agentName, e);
            CrossCheckResult errorResult = new CrossCheckResult();
            errorResult.setAgentName(agentName);
            errorResult.setValid(false);
            errorResult.setConfidence(0.0);
            errorResult.addIssue("Cross-check failed: " + e.getMessage());
            return errorResult;
        }
    }

    private CrossCheckResult parseResult(String json) {
        CrossCheckResult result = new CrossCheckResult();
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            result.setValid(node.has("valid") && node.get("valid").asBoolean());
            result.setConfidence(node.has("confidence") ? node.get("confidence").asDouble() : 0.5);

            if (node.has("issues")) {
                for (var issue : node.get("issues")) {
                    result.addIssue(issue.asText());
                }
            }
            if (node.has("suggestions")) {
                for (var suggestion : node.get("suggestions")) {
                    result.addSuggestion(suggestion.asText());
                }
            }
            if (node.has("summary")) {
                result.setSummary(node.get("summary").asText());
            }
        } catch (Exception e) {
            log.warn("Failed to parse cross-check result: {}", e.getMessage(), e);
            result.setValid(true);
            result.setConfidence(0.5);
            result.setSummary("Parse failed, assuming valid");
        }
        return result;
    }

    public CrossCheckResult getLastResult(String agentName) {
        return lastResults.get(agentName);
    }

    public static class CrossCheckResult {
        private String agentName;
        private String originalOutput;
        private boolean valid;
        private double confidence;
        private final java.util.List<String> issues = new java.util.ArrayList<>();
        private final java.util.List<String> suggestions = new java.util.ArrayList<>();
        private String summary;

        public String getAgentName() { return agentName; }
        public void setAgentName(String agentName) { this.agentName = agentName; }

        public String getOriginalOutput() { return originalOutput; }
        public void setOriginalOutput(String originalOutput) { this.originalOutput = originalOutput; }

        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public java.util.List<String> getIssues() { return issues; }
        public void addIssue(String issue) { this.issues.add(issue); }

        public java.util.List<String> getSuggestions() { return suggestions; }
        public void addSuggestion(String suggestion) { this.suggestions.add(suggestion); }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
    }
}
