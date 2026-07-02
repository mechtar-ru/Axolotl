package com.agent.orchestrator.llm;

import com.agent.orchestrator.service.SettingsService;
import jakarta.annotation.PostConstruct;
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

@Component("zenProvider")
public class OpencodeZenProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpencodeZenProvider.class);

    static final String DEFAULT_BASE_URL = "https://opencode.ai/zen/v1";
    private static final int TIMEOUT_SECONDS = 300;

    private final SettingsService settingsService;

    @Value("${axolotl.llm.zen.base-url:https://opencode.ai/zen/v1}")
    private String baseUrl;

    @Value("${axolotl.llm.zen.api-key:}")
    private String apiKey;

    @Value("${axolotl.llm.zen.default-model:deepseek-v4-flash-free}")
    private String defaultModel;

    public OpencodeZenProvider(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @PostConstruct
    public void checkConfig() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Zen API key not configured. Set ZEN_API_KEY env var to use Zen provider.");
        }
    }

    private String getEffectiveApiKey() {
        String key = settingsService.getApiKey("zen");
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
            return textOnly("Zen: API key not configured");
        }

        List<Map<String, Object>> toolsList = extractTools(config);
        try {
            return OpenAiChatClient.chat(getEffectiveApiKey(), baseUrl, effectiveModel,
                    systemPrompt, userPrompt, usage, TIMEOUT_SECONDS,
                    java.net.http.HttpClient.Version.HTTP_1_1, toolsList);
        } catch (Exception e) {
            log.warn("Zen chat error, retrying once: {}", e.getMessage(), e);
            try {
                return OpenAiChatClient.chat(getEffectiveApiKey(), baseUrl, effectiveModel,
                        systemPrompt, userPrompt, usage, TIMEOUT_SECONDS,
                        java.net.http.HttpClient.Version.HTTP_1_1, toolsList);
            } catch (Exception e2) {
                String error = "Zen error: " + e2.getMessage();
                log.error(error, e2);
                return textOnly(error);
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return getEffectiveApiKey() != null && !getEffectiveApiKey().isBlank();
    }

    @Override
    public String getName() {
        return "zen";
    }

    @Override
    public List<String> listModels() {
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) return List.of();
        try {
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .build();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/models"))
                    .header("Authorization", "Bearer " + getEffectiveApiKey())
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = mapper.readTree(response.body());
                var data = root.path("data");
                List<String> models = new ArrayList<>();
                if (data.isArray()) {
                    for (var m : data) {
                        String id = m.path("id").asText();
                        if (id != null && !id.isBlank()) {
                            models.add(id);
                        }
                    }
                }
                return models;
            } else {
                log.warn("Zen models API returned {}", response.statusCode());
            }
        } catch (Exception e) {
            log.warn("Zen models API unavailable: {}", e.getMessage(), e);
        }
        return List.of();
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
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
            String error = "Zen: API key not configured";
            onToken.accept(error);
            return textOnly(error);
        }

        try {
            return OpenAiChatClient.streamingChat(getEffectiveApiKey(), baseUrl, effectiveModel,
                    systemPrompt, userPrompt, onToken, TIMEOUT_SECONDS,
                    java.net.http.HttpClient.Version.HTTP_1_1);
        } catch (Exception e) {
            log.warn("Zen streaming error, retrying once: {}", e.getMessage(), e);
            try {
                return OpenAiChatClient.streamingChat(getEffectiveApiKey(), baseUrl, effectiveModel,
                        systemPrompt, userPrompt, onToken, TIMEOUT_SECONDS,
                        java.net.http.HttpClient.Version.HTTP_1_1);
            } catch (Exception e2) {
                String error = "Zen streaming error: " + e2.getMessage();
                log.error(error, e2);
                onToken.accept(error);
                return textOnly(error);
            }
        }
    }

    private String resolveModel(String model) {
        if (model == null || model.isBlank() || isProviderName(model)) return defaultModel;
        return model;
    }

    private boolean isProviderName(String model) {
        return "zen".equalsIgnoreCase(model) || "opencode".equalsIgnoreCase(model);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTools(Map<String, Object> config) {
        if (config == null) return null;
        Object tools = config.get("_tools");
        if (tools == null) tools = config.get("tools");
        if (tools instanceof List) {
            return (List<Map<String, Object>>) tools;
        }
        return null;
    }
}
