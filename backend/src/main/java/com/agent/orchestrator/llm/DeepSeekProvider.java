package com.agent.orchestrator.llm;

import com.agent.orchestrator.service.SettingsService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class DeepSeekProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekProvider.class);

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
        this.settingsService = settingsService;
    }

    private String getEffectiveApiKey() {
        String key = settingsService.getApiKey("deepseek");
        if (key != null && !key.isBlank()) return key;
        return apiKey;
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        return chat(model, systemPrompt, userPrompt, config, null);
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt,
                       Map<String, Object> config, LlmUsage usage) {
        String effectiveModel = resolveModel(model);
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) {
            return "DeepSeek: API key not configured";
        }

        try {
            ChatLanguageModel chatModel = OpenAiChatModel.builder()
                    .apiKey(getEffectiveApiKey())
                    .modelName(effectiveModel)
                    .baseUrl(baseUrl)
                    .temperature(0.7)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new SystemMessage(systemPrompt));
            }
            messages.add(new UserMessage(userPrompt));

            ChatResponse response = chatModel.chat(messages);
            String content = response.aiMessage().text();
            if (usage != null && response.tokenUsage() != null) {
                usage.setInputTokens(response.tokenUsage().inputTokenCount());
                usage.setOutputTokens(response.tokenUsage().outputTokenCount());
                usage.setTotalTokens(response.tokenUsage().totalTokenCount());
            }
            int tokens = response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : 0;
            log.info("DeepSeek response ({} tokens): {}", tokens,
                    content.length() > 100 ? content.substring(0, 100) + "..." : content);
            return content;
        } catch (Exception e) {
            String error = "DeepSeek error: " + e.getMessage();
            log.error(error, e);
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
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) return List.of();
        try {
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/models"))
                    .header("Authorization", "Bearer " + getEffectiveApiKey())
                    .GET()
                    .timeout(Duration.ofSeconds(5))
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
            }
        } catch (Exception e) {
            log.warn("DeepSeek models API unavailable: {}", e.getMessage());
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
        return "deepseek".equalsIgnoreCase(model);
    }
}
