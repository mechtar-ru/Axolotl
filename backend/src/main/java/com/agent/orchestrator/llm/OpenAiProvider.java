package com.agent.orchestrator.llm;

import com.agent.orchestrator.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.agent.orchestrator.llm.LlmResponse.textOnly;

@Component
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);

    private final SettingsService settingsService;

    @Value("${axolotl.llm.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${axolotl.llm.openai.api-key:}")
    private String apiKey;

    @Value("${axolotl.llm.openai.default-model:gpt-4o-mini}")
    private String defaultModel;

    @Value("${axolotl.llm.openai.timeout:120}")
    private int timeoutSeconds;

    public OpenAiProvider(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    private String getEffectiveApiKey() {
        String key = settingsService.getApiKey("openai");
        if (key != null && !key.isBlank()) return key;
        return apiKey;
    }

    @Override
    public LlmResponse chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        return chat(model, systemPrompt, userPrompt, config, null);
    }

    @Override
    public LlmResponse chat(String model, String systemPrompt, String userPrompt,
                       Map<String, Object> config, LlmUsage usage) {
        String effectiveModel = resolveModel(model);
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) {
            return textOnly("OpenAI: API key not configured");
        }

        try {
            return OpenAiChatClient.chat(getEffectiveApiKey(), baseUrl, effectiveModel,
                    systemPrompt, userPrompt, usage, timeoutSeconds);
        } catch (Exception e) {
            String error = "OpenAI error: " + e.getMessage();
            log.error(error, e);
            return textOnly(error);
        }
    }

    @Override
    public boolean isAvailable() {
        return getEffectiveApiKey() != null && !getEffectiveApiKey().isBlank();
    }

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public List<String> listModels() {
        String effectiveKey = getEffectiveApiKey();
        if (effectiveKey == null || effectiveKey.isBlank()) return List.of();
        try {
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/models"))
                    .header("Authorization", "Bearer " + effectiveKey)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = mapper.readTree(response.body());
                var data = root.path("data");
                List<String> models = new ArrayList<>();
                for (var m : data) {
                    String id = m.path("id").asText("");
                    if (!id.isBlank()) models.add(id);
                }
                return models;
            }
        } catch (Exception e) {
            log.warn("OpenAI models API unavailable: {}", e.getMessage(), e);
        }
        return List.of();
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
        return "openai".equalsIgnoreCase(model) || "gpt".equalsIgnoreCase(model);
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public LlmResponse streamingChat(String model, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> onToken) {
        String effectiveModel = resolveModel(model);
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) {
            String error = "OpenAI: API key not configured";
            onToken.accept(error);
            return textOnly(error);
        }

        try {
            return OpenAiChatClient.streamingChat(getEffectiveApiKey(), baseUrl, effectiveModel,
                    systemPrompt, userPrompt, onToken, timeoutSeconds);
        } catch (Exception e) {
            String error = "OpenAI streaming error: " + e.getMessage();
            log.error(error, e);
            onToken.accept(error);
            return textOnly(error);
        }
    }
}
