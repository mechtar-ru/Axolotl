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
import java.util.*;
import java.util.function.Consumer;

@Component
public class LlamaCppProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(LlamaCppProvider.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${axolotl.llm.llama-cpp.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${axolotl.llm.llama-cpp.default-model:Ternary-Bonsai-8B-Q2_0.gguf}")
    private String defaultModel;

    @Value("${axolotl.llm.llama-cpp.timeout:300}")
    private int timeout;

    private final HttpClient httpClient;

    public LlamaCppProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String getName() {
        return "llama-cpp";
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("LlamaCpp not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> listModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                List<String> models = new ArrayList<>();
                if (root.has("data")) {
                    for (JsonNode model : root.get("data")) {
                        if (model.has("id")) {
                            models.add(model.get("id").asText());
                        }
                    }
                }
                return models;
            }
        } catch (Exception e) {
            log.error("Error listing LlamaCpp models: {}", e.getMessage());
        }
        return List.of(defaultModel);
    }

    @Override
    public String chat(String modelHint, String systemPrompt, String userPrompt, Map<String, Object> options) {
        String effectiveModel = (modelHint != null && !modelHint.isBlank()) ? modelHint : defaultModel;

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", effectiveModel);

            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", systemPrompt);
                messages.add(systemMsg);
            }
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            requestBody.put("messages", messages);
            requestBody.put("stream", false);

            if (options != null) {
                if (options.containsKey("temperature")) {
                    requestBody.put("temperature", options.get("temperature"));
                }
                if (options.containsKey("max_tokens")) {
                    requestBody.put("max_tokens", options.get("max_tokens"));
                }
            }

            String jsonBody = mapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.info("LlamaCpp请求: model={}, prompt={}...", effectiveModel,
                    userPrompt.substring(0, Math.min(50, userPrompt.length())));

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                String content = root.path("choices").get(0).path("message").path("content").asText("");
                log.info("LlamaCpp回答: {}...", content.substring(0, Math.min(100, content.length())));
                return content;
            } else {
                String error = "LlamaCpp错误 (HTTP " + response.statusCode() + "): " + response.body();
                log.error(error);
                return error;
            }
        } catch (Exception e) {
            String error = "LlamaCpp请求失败: " + e.getMessage();
            log.error(error, e);
            return error;
        }
    }

    @Override
    public String streamingChat(String modelHint, String systemPrompt, String userPrompt, Map<String, Object> options,
                               Consumer<String> tokenConsumer) {
        String effectiveModel = (modelHint != null && !modelHint.isBlank()) ? modelHint : defaultModel;

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", effectiveModel);

            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", systemPrompt);
                messages.add(systemMsg);
            }
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            requestBody.put("messages", messages);
            requestBody.put("stream", true);

            if (options != null) {
                if (options.containsKey("temperature")) {
                    requestBody.put("temperature", options.get("temperature"));
                }
            }

            String jsonBody = mapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.info("LlamaCpp streaming请求: model={}", effectiveModel);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                StringBuilder fullContent = new StringBuilder();

                for (String line : body.split("\n")) {
                    if (line.startsWith("data: ")) {
                        String json = line.substring(6);
                        if ("[DONE]".equals(json)) continue;

                        try {
                            JsonNode root = mapper.readTree(json);
                            String content = root.path("choices").get(0).path("delta").path("content").asText("");
                            if (!content.isEmpty()) {
                                tokenConsumer.accept(content);
                                fullContent.append(content);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }

                log.info("LlamaCpp streaming完成");
                return fullContent.toString();
            } else {
                String error = "LlamaCpp streaming错误 (HTTP " + response.statusCode() + "): " + response.body();
                log.error(error);
                tokenConsumer.accept(error);
                return error;
            }
        } catch (Exception e) {
            String error = "LlamaCpp streaming请求失败: " + e.getMessage();
            log.error(error, e);
            tokenConsumer.accept(error);
            return error;
        }
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public boolean supportsModel(String model) {
        if (model == null || model.isBlank()) return false;
        String lower = model.toLowerCase();
        return lower.contains("gguf") || lower.contains("llama") || lower.contains("bonsai") ||
                lower.equals("llama-cpp") || lower.equals("llamacpp");
    }
}