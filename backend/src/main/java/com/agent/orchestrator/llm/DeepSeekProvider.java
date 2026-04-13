package com.agent.orchestrator.llm;

import com.agent.orchestrator.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DeepSeekProvider implements LlmProvider {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Value("${axolotl.llm.deepseek.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${axolotl.llm.deepseek.api-key:}")
    private String apiKey;

    @Value("${axolotl.llm.deepseek.default-model:deepseek-chat}")
    private String defaultModel;

    @Value("${axolotl.llm.deepseek.timeout:120}")
    private int timeoutSeconds;

    public DeepSeekProvider(SettingsService settingsService) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.settingsService = settingsService;
    }

    private String getEffectiveApiKey() {
        String key = settingsService.getApiKey("deepseek");
        if (key != null && !key.isBlank()) return key;
        return apiKey;
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        String effectiveModel = resolveModel(model);
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) {
            return "DeepSeek: API ключ не настроен. Установите axolotl.llm.deepseek.api-key в application.properties";
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
                    .header("Authorization", "Bearer " + getEffectiveApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            System.out.println("🔍 DeepSeek запрос: model=" + effectiveModel);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                int tokens = root.path("usage").path("total_tokens").asInt(0);
                System.out.println("🔍 DeepSeek ответ (" + tokens + " токенов): " +
                        content.substring(0, Math.min(100, content.length())) + "...");
                return content;
            } else {
                String error = "DeepSeek ошибка (HTTP " + response.statusCode() + "): " + response.body();
                System.err.println("❌ " + error);
                return error;
            }
        } catch (Exception e) {
            String error = "DeepSeek недоступен: " + e.getMessage();
            System.err.println("❌ " + error);
            return error;
        }
    }

    @Override
    public boolean isAvailable() {
        return getEffectiveApiKey() != null && !getEffectiveApiKey().isBlank();
    }

    @Override
    public String getName() {
        return "deepseek";
    }

    @Override
    public List<String> listModels() {
        return List.of("deepseek-chat", "deepseek-reasoner");
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
        return "deepseek".equalsIgnoreCase(model);
    }
}
