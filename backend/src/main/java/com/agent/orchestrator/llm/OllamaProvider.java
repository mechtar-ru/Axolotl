package com.agent.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${axolotl.llm.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${axolotl.llm.ollama.default-model:gemma4:e2b}")
    private String defaultModel;

    @Value("${axolotl.llm.ollama.timeout:120}")
    private int timeoutSeconds;

    public OllamaProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        // Если передано имя провайдера ("ollama", "local") вместо модели — использовать дефолтную модель
        String effectiveModel = defaultModel;
        if (model != null && !model.isBlank() && !isProviderName(model)) {
            effectiveModel = model;
        }

        try {
            var messages = new ArrayList<Map<String, String>>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            var requestBody = new java.util.HashMap<String, Object>();
            requestBody.put("model", effectiveModel);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            log.info("Ollama запрос: model={}, prompt={}...", effectiveModel,
                    userPrompt.substring(0, Math.min(80, userPrompt.length())));

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode messageNode = root.path("message");
                String content = messageNode.path("content").asText("");
                log.info("Ollama ответ: {}...", content.substring(0, Math.min(100, content.length())));
                return content;
            } else {
                String error = "Ollama ошибка (HTTP " + response.statusCode() + "): " + response.body();
                log.error(error);
                return error;
            }
        } catch (Exception e) {
            String error = "Ollama недоступен: " + e.getMessage();
            log.error(error);
            return error;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String streamingChat(String model, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> onToken) {
        String effectiveModel = defaultModel;
        if (model != null && !model.isBlank() && !isProviderName(model)) {
            effectiveModel = model;
        }

        try {
            var messages = new ArrayList<Map<String, String>>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            var requestBody = new java.util.HashMap<String, Object>();
            requestBody.put("model", effectiveModel);
            requestBody.put("messages", messages);
            requestBody.put("stream", true);

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            log.info("Ollama streaming запрос: model={}", effectiveModel);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            StringBuilder fullResponse = new StringBuilder();

            // Ollama streams NDJSON — one JSON object per line
            BufferedReader reader = new BufferedReader(new StringReader(response.body()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = objectMapper.readTree(line);
                    String token = node.path("message").path("content").asText("");
                    if (!token.isEmpty()) {
                        fullResponse.append(token);
                        onToken.accept(token);
                    }
                    if (node.path("done").asBoolean(false)) break;
                } catch (Exception ignored) {}
            }

            return fullResponse.toString();
        } catch (Exception e) {
            String error = "Ollama streaming недоступен: " + e.getMessage();
            log.error(error);
            onToken.accept(error);
            return error;
        }
    }

    @Override
    public String getName() {
        return "ollama";
    }

    private boolean isProviderName(String model) {
        return "ollama".equalsIgnoreCase(model) || "local".equalsIgnoreCase(model);
    }

    @Override
    public List<String> listModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode modelsNode = root.path("models");
                List<String> models = new ArrayList<>();
                for (JsonNode model : modelsNode) {
                    models.add(model.path("name").asText(""));
                }
                return models;
            }
        } catch (Exception e) {
            log.error("Ошибка получения списка моделей Ollama: {}", e.getMessage());
        }
        return List.of();
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }
}
