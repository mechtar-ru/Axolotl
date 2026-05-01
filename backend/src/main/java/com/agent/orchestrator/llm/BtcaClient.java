package com.agent.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client for BTCA (Better Tech Context Awareness) CLI.
 * Provides technology lookup and code analysis capabilities.
 */
@Component
public class BtcaClient implements KnowledgeAugmentor {

    private static final Logger log = LoggerFactory.getLogger(BtcaClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${axolotl.btca.base-url:http://localhost:7429}")
    private String baseUrl;

    @Value("${axolotl.btca.enabled:false}")
    private boolean enabled;

    public BtcaClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Ask BTCA a question about a specific technology.
     * @param tech The technology/library name (e.g., "vue", "react")
     * @param question The question to ask
     * @return The answer from BTCA
     */
    public String ask(String tech, String question) {
        if (!enabled) {
            return "[BTCA not configured. Set axolotl.btca.enabled=true in application.yml]";
        }

        try {
            String url = baseUrl + "/api/ask";
            String body = objectMapper.writeValueAsString(Map.of(
                "tech", tech,
                "question", question
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "BTCA request failed: HTTP " + response.statusCode() + " - " + response.body();
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.has("answer") ? root.get("answer").asText() : response.body();

        } catch (Exception e) {
            log.error("BTCA ask failed: {}", e.getMessage());
            return "BTCA error: " + e.getMessage();
        }
    }

    /**
     * Detect tech keywords in prompt, query BTCA, return formatted context block.
     * Returns empty string if disabled or no tech detected.
     */
    public String getKnowledgeForPrompt(String prompt) {
        if (!enabled) {
            return "";
        }
        // Simple tech detection: look for common tech keywords
        String[] techKeywords = {"vue", "react", "spring", "express", "django", "flask",
                "postgresql", "mysql", "mongodb", "redis", "docker", "kubernetes"};
        String detectedTech = null;
        String promptLower = prompt.toLowerCase();
        for (String tech : techKeywords) {
            if (promptLower.contains(tech)) {
                detectedTech = tech;
                break;
            }
        }
        if (detectedTech == null) {
            return "";
        }
        try {
            String answer = ask(detectedTech, "What are the key patterns, best practices, and common pitfalls when using " + detectedTech + "?");
            if (answer == null || answer.isBlank() || answer.startsWith("BTCA")) {
                return "";
            }
            return "\n\n## Pre-Execution Knowledge (" + detectedTech + ")\n" + answer.substring(0, Math.min(500, answer.length())) + "\n";
        } catch (Exception e) {
            log.warn("BTCA knowledge lookup failed: {}", e.getMessage());
            return "";
        }
    }
}
