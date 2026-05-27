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

@Component("zenProvider")
public class OpencodeZenProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpencodeZenProvider.class);

    static final String DEFAULT_BASE_URL = "https://opencode.ai/zen/v1";
    private static final int TIMEOUT_SECONDS = 3600;

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

    private String getEffectiveApiKey() {
        String key = settingsService.getApiKey("zen");
        if (key != null && !key.isBlank()) return key;
        return apiKey;
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        String effectiveModel = resolveModel(model);
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) {
            return "Zen: API key not configured";
        }

        try {
            ChatLanguageModel chatModel = createChatModel(effectiveModel);
            List<ChatMessage> messages = buildMessages(systemPrompt, userPrompt);
            ChatResponse response = chatModel.chat(messages);
            return response.aiMessage().text();
        } catch (Exception e) {
            log.warn("Zen chat error, retrying once: {}", e.getMessage());
            try {
                ChatLanguageModel chatModel = createChatModel(effectiveModel);
                List<ChatMessage> messages = buildMessages(systemPrompt, userPrompt);
                ChatResponse response = chatModel.chat(messages);
                return response.aiMessage().text();
            } catch (Exception e2) {
                String error = "Zen error: " + e2.getMessage();
                log.error(error, e2);
                return error;
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
            log.warn("Zen models API unavailable: {}", e.getMessage());
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
    public String streamingChat(String model, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> onToken) {
        String effectiveModel = resolveModel(model);
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) {
            String error = "Zen: API key not configured";
            onToken.accept(error);
            return error;
        }

        try {
            StreamingChatLanguageModel streamingModel = createStreamingChatModel(effectiveModel);
            List<ChatMessage> messages = buildMessages(systemPrompt, userPrompt);

            StringBuilder fullResponse = new StringBuilder();
            streamingModel.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    fullResponse.append(token);
                    onToken.accept(token);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    log.info("Zen streaming complete");
                }

                @Override
                public void onError(Throwable error) {
                    log.error("Zen streaming error: {}", error.getMessage());
                }
            });
            return fullResponse.toString();
        } catch (Exception e) {
            log.warn("Zen streaming error, retrying once: {}", e.getMessage());
            try {
                StreamingChatLanguageModel streamingModel = createStreamingChatModel(effectiveModel);
                List<ChatMessage> messages = buildMessages(systemPrompt, userPrompt);

                StringBuilder fullResponse = new StringBuilder();
                streamingModel.chat(messages, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        fullResponse.append(token);
                        onToken.accept(token);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        log.info("Zen streaming retry complete");
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("Zen streaming retry error: {}", error.getMessage());
                    }
                });
                return fullResponse.toString();
            } catch (Exception e2) {
                String error = "Zen streaming error: " + e2.getMessage();
                log.error(error, e2);
                onToken.accept(error);
                return error;
            }
        }
    }

    private ChatLanguageModel createChatModel(String modelName) {
        return OpenAiChatModel.builder()
                .apiKey(getEffectiveApiKey())
                .modelName(modelName)
                .baseUrl(baseUrl)
                .temperature(0.7)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    private StreamingChatLanguageModel createStreamingChatModel(String modelName) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(getEffectiveApiKey())
                .modelName(modelName)
                .baseUrl(baseUrl)
                .temperature(0.7)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();
    }

    private static List<ChatMessage> buildMessages(String systemPrompt, String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));
        return messages;
    }

    private String resolveModel(String model) {
        if (model == null || model.isBlank() || isProviderName(model)) return defaultModel;
        return model;
    }

    private boolean isProviderName(String model) {
        return "zen".equalsIgnoreCase(model) || "opencode".equalsIgnoreCase(model);
    }
}
