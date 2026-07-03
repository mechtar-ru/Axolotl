package com.agent.orchestrator.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Loads and provides model-specific system prompts.
 * Prompts are stored in resources/prompts/{model}.txt and resources/prompts/default.txt.
 * Model selection is based on model name prefix matching.
 */
@Component
public class ModelPrompts {

    private static final Logger log = LoggerFactory.getLogger(ModelPrompts.class);

    private final Map<String, String> prompts = new HashMap<>();
    private String defaultPrompt = "";

    @PostConstruct
    public void init() {
        defaultPrompt = loadPrompt("default");
        loadPrompt("deepseek");
        loadPrompt("claude");
        log.info("Loaded {} model-specific prompts", prompts.size());
    }

    /**
     * Get the best system prompt for the given model name.
     * Matches by prefix (e.g., "deepseek-v4" → deepseek prompt).
     * Falls back to default if no match.
     */
    public String getPrompt(String model) {
        if (model == null || model.isBlank()) return defaultPrompt;

        // Try prefix matching (longest match first)
        String matched = null;
        int maxLen = 0;
        for (String prefix : prompts.keySet()) {
            // Strip provider prefix if present (e.g., "zen:deepseek-v4" → "deepseek-v4")
            String stripped = model.contains(":") ? model.substring(model.indexOf(':') + 1) : model;
            if (stripped.startsWith(prefix) && prefix.length() > maxLen) {
                matched = prefix;
                maxLen = prefix.length();
            }
        }
        if (matched != null) {
            String p = prompts.get(matched);
            if (p != null && !p.isBlank()) return p;
        }
        return defaultPrompt;
    }

    public String getDefaultPrompt() {
        return defaultPrompt;
    }

    private String loadPrompt(String name) {
        try {
            var resource = new ClassPathResource("prompts/" + name + ".txt");
            if (!resource.exists()) {
                log.warn("Prompt file not found: prompts/{}.txt", name);
                return "";
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String text = reader.lines().collect(Collectors.joining("\n"));
                if (!"default".equals(name)) {
                    prompts.put(name, text);
                }
                return text;
            }
        } catch (Exception e) {
            log.warn("Failed to load prompt {}: {}", name, e.getMessage());
            return "";
        }
    }
}
