package com.agent.orchestrator.llm;

import com.agent.orchestrator.service.SettingsService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
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
public class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    private final SettingsService settingsService;

    @Value("${axolotl.llm.anthropic.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Value("${axolotl.llm.anthropic.api-key:}")
    private String apiKey;

    @Value("${axolotl.llm.anthropic.default-model:claude-sonnet-4-20250514}")
    private String defaultModel;

    @Value("${axolotl.llm.anthropic.timeout:120}")
    private int timeoutSeconds;

    public AnthropicProvider(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    private String getEffectiveApiKey() {
        String key = settingsService.getApiKey("anthropic");
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
            return textOnly("Anthropic: API key not configured");
        }

        try {
            ChatLanguageModel chatModel = AnthropicChatModel.builder()
                    .apiKey(getEffectiveApiKey())
                    .modelName(effectiveModel)
                    .baseUrl(baseUrl)
                    .temperature(0.7)
                    .maxTokens(4096)
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
            log.info("Anthropic response: {}",
                    content.length() > 100 ? content.substring(0, 100) + "..." : content);
            return textOnly(content);
        } catch (Exception e) {
            String error = "Anthropic error: " + e.getMessage();
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
        return "anthropic";
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
                    .uri(java.net.URI.create(baseUrl + "/v1/models"))
                    .header("x-api-key", effectiveKey)
                    .header("anthropic-version", "2023-06-01")
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
            log.warn("Anthropic models API unavailable: {}", e.getMessage(), e);
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
        return "anthropic".equalsIgnoreCase(model) || "claude".equalsIgnoreCase(model);
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
            String error = "Anthropic: API key not configured";
            onToken.accept(error);
            return textOnly(error);
        }

        try {
            StreamingChatLanguageModel streamingModel = AnthropicStreamingChatModel.builder()
                    .apiKey(getEffectiveApiKey())
                    .modelName(effectiveModel)
                    .baseUrl(baseUrl)
                    .temperature(0.7)
                    .maxTokens(4096)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new SystemMessage(systemPrompt));
            }
            messages.add(new UserMessage(userPrompt));

            StringBuilder fullResponse = new StringBuilder();
            streamingModel.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    fullResponse.append(token);
                    onToken.accept(token);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    log.info("Anthropic streaming complete");
                }

                @Override
                public void onError(Throwable error) {
                    log.error("Anthropic streaming error: {}", error.getMessage(), error);
                }
            });

            return textOnly(fullResponse.toString());
        } catch (Exception e) {
            String error = "Anthropic streaming error: " + e.getMessage();
            log.error(error, e);
            onToken.accept(error);
            return textOnly(error);
        }
    }
}
