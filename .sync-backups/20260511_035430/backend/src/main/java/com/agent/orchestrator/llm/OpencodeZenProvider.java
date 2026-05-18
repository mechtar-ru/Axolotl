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
public class OpencodeZenProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpencodeZenProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${axolotl.llm.zen.base-url:https://opencode.ai/zen/v1}")
    private String baseUrl;

    @Value("${axolotl.llm.zen.api-key:}")
    private String apiKey;

    @Value("${axolotl.llm.zen.default-model:big-pickle}")
    private String defaultModel;

    @Value("${axolotl.llm.zen.timeout:3600}")
    private int timeoutSeconds;

    private static final List<String> ZEN_MODELS = List.of(
        "big-pickle", "hy3-preview-free", "ling-2.6-flash-free", "trinity-large-preview-free", 
        "nemotron-3-super-free", "minimax-m2.5-free",
        "claude-opus-4.7", "claude-opus-4.6", "claude-opus-4.5", "claude-opus-4.1",
        "claude-sonnet-4.6", "claude-sonnet-4.5", "claude-sonnet-4", "claude-haiku-4.5",
        "gemini-3.1-pro", "gemini-3-flash",
        "gpt-5.5", "gpt-5.5-pro", "gpt-5.4", "gpt-5.4-pro", "gpt-5.4-mini", "gpt-5.4-nano",
        "gpt-5.3-codex-spark", "gpt-5.3-codex", "gpt-5.2", "gpt-5.2-codex", "gpt-5.1",
        "gpt-5.1-codex-max", "gpt-5.1-codex", "gpt-5.1-codex-mini", "gpt-5", "gpt-5-codex", "gpt-5-nano",
        "glm-5.1", "glm-5",
        "minimax-m2.7", "minimax-m2.5",
        "kimi-k2.6", "kimi-k2.5",
        "qwen3.6-plus", "qwen3.5-plus"
    );

    public OpencodeZenProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        String effectiveModel = resolveModel(model);
        
        if (apiKey == null || apiKey.isBlank()) {
            return "OpenCode Zen: API ключ не настроен. Установите ZEN_API_KEY в .env файле";
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
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            log.info("OpenCode Zen запрос: model={}", effectiveModel);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                int tokens = root.path("usage").path("total_tokens").asInt(0);
                log.info("OpenCode Zen ответ ({} токенов): {}", tokens, 
                        content.substring(0, Math.min(100, content.length())));
                return content;
            } else {
                String error = "OpenCode Zen ошибка (HTTP " + response.statusCode() + "): " + response.body();
                log.error(error);
                return error;
            }
        } catch (Exception e) {
            String error = "OpenCode Zen недоступен: " + e.getMessage();
            log.error(error);
            return error;
        }
    }

    @Override
    public boolean isAvailable() {
        if (apiKey == null || apiKey.isBlank()) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "zen";
    }

    @Override
    public List<String> listModels() {
        return ZEN_MODELS;
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    private String resolveModel(String model) {
        if (model == null || model.isBlank() || isProviderName(model)) return defaultModel;
        return model;
    }

    private boolean isProviderName(String model) {
        return "zen".equalsIgnoreCase(model) || "opencode".equalsIgnoreCase(model);
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String streamingChat(String model, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> onToken) {
        String effectiveModel = resolveModel(model);
        
        if (apiKey == null || apiKey.isBlank()) {
            String error = "OpenCode Zen: API ключ не настроен";
            onToken.accept(error);
            return error;
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
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            log.info("OpenCode Zen streaming запрос: model={}", effectiveModel);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            StringBuilder fullResponse = new StringBuilder();

            BufferedReader reader = new BufferedReader(new StringReader(response.body()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data: ")) continue;
                String data = line.substring(6);
                if ("[DONE]".equals(data)) break;

                try {
                    JsonNode node = objectMapper.readTree(data);
                    String token = node.path("choices").path(0).path("delta").path("content").asText("");
                    if (!token.isEmpty()) {
                        fullResponse.append(token);
                        onToken.accept(token);
                    }
                } catch (Exception ignored) {}
            }

            return fullResponse.toString();
        } catch (Exception e) {
            String error = "OpenCode Zen streaming недоступен: " + e.getMessage();
            log.error(error);
            onToken.accept(error);
            return error;
        }
    }
}