package com.agent.orchestrator.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
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
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    @Value("${axolotl.llm.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${axolotl.llm.ollama.default-model:gemma4:e2b}")
    private String defaultModel;

    @Value("${axolotl.llm.ollama.timeout:3600}")
    private int timeoutSeconds;

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        return chat(model, systemPrompt, userPrompt, config, null);
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt,
                       Map<String, Object> config, LlmUsage usage) {
        String effectiveModel = resolveModel(model);

        try {
            ChatLanguageModel chatModel = OllamaChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(effectiveModel)
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
            log.info("Ollama response: {}",
                    content.length() > 100 ? content.substring(0, 100) + "..." : content);
            return content;
        } catch (Exception e) {
            String error = "Ollama error: " + e.getMessage();
            log.error(error, e);
            return error;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
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
        String effectiveModel = resolveModel(model);

        try {
            StreamingChatLanguageModel streamingModel = OllamaStreamingChatModel.builder()
                    .baseUrl(baseUrl)
                    .modelName(effectiveModel)
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
                    log.info("Ollama streaming complete");
                }

                @Override
                public void onError(Throwable error) {
                    log.error("Ollama streaming error: {}", error.getMessage());
                }
            });

            return fullResponse.toString();
        } catch (Exception e) {
            String error = "Ollama streaming error: " + e.getMessage();
            log.error(error, e);
            onToken.accept(error);
            return error;
        }
    }

    @Override
    public String getName() {
        return "ollama";
    }

    private String resolveModel(String model) {
        if (model == null || model.isBlank() || isProviderName(model)) return defaultModel;
        return model;
    }

    private boolean isProviderName(String model) {
        return "ollama".equalsIgnoreCase(model) || "local".equalsIgnoreCase(model);
    }

    @Override
    public List<String> listModels() {
        try {
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = mapper.readTree(response.body());
                var modelsNode = root.path("models");
                List<String> models = new ArrayList<>();
                for (var m : modelsNode) {
                    models.add(m.path("name").asText(""));
                }
                return models;
            }
        } catch (Exception e) {
            log.error("Ollama listModels error: {}", e.getMessage());
        }
        return List.of();
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }
}
