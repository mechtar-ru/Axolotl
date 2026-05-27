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
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        String effectiveModel = resolveModel(model);
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) {
            return "OpenAI: API key not configured";
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
            int tokens = response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : 0;
            log.info("OpenAI response ({} tokens): {}", tokens,
                    content.length() > 100 ? content.substring(0, 100) + "..." : content);
            return content;
        } catch (Exception e) {
            String error = "OpenAI error: " + e.getMessage();
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
            log.warn("OpenAI models API unavailable: {}", e.getMessage());
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
    public String streamingChat(String model, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> onToken) {
        String effectiveModel = resolveModel(model);
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) {
            String error = "OpenAI: API key not configured";
            onToken.accept(error);
            return error;
        }

        try {
            StreamingChatLanguageModel streamingModel = OpenAiStreamingChatModel.builder()
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

            StringBuilder fullResponse = new StringBuilder();
            streamingModel.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    fullResponse.append(token);
                    onToken.accept(token);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    log.info("OpenAI streaming complete: {} tokens",
                            response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : 0);
                }

                @Override
                public void onError(Throwable error) {
                    log.error("OpenAI streaming error: {}", error.getMessage());
                }
            });

            return fullResponse.toString();
        } catch (Exception e) {
            String error = "OpenAI streaming error: " + e.getMessage();
            log.error(error, e);
            onToken.accept(error);
            return error;
        }
    }
}
