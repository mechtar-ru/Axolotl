package com.agent.orchestrator.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM Provider implementation using Spring AI.
 * Wraps Spring AI ChatModel to implement the LlmProvider interface.
 */
@Component
public class SpringAiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmProvider.class);

    private final OllamaChatModel ollamaChatModel;
    private final OpenAiChatModel openAiChatModel;
    private final ChatClient ollamaChatClient;
    private final ChatClient openAiChatClient;

    public SpringAiLlmProvider(
            OllamaChatModel ollamaChatModel,
            OpenAiChatModel openAiChatModel,
            @Qualifier("ollamaChatClient") ChatClient ollamaChatClient,
            @Qualifier("openAiChatClient") ChatClient openAiChatClient) {
        this.ollamaChatModel = ollamaChatModel;
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatClient = ollamaChatClient;
        this.openAiChatClient = openAiChatClient;
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        String provider = resolveProvider(model);

        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));

        try {
            if ("openai".equals(provider) || isOpenAiModel(model)) {
                log.info("Spring AI OpenAI chat: model={}", model);
                String content = openAiChatClient.prompt()
                        .messages(messages)
                        .options(OpenAiChatOptions.builder()
                                .model(model)
                                .temperature(getTemperature(config))
                                .build())
                        .call()
                        .content();
                return content != null ? content : "";
            } else {
                log.info("Spring AI Ollama chat: model={}", model);
                String content = ollamaChatClient.prompt()
                        .messages(messages)
                        .options(OllamaChatOptions.builder()
                                .model(model)
                                .temperature(getTemperature(config))
                                .build())
                        .call()
                        .content();
                return content != null ? content : "";
            }
        } catch (Exception e) {
            log.error("Spring AI chat error: {}", e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public String streamingChat(String model, String systemPrompt, String userPrompt,
                               Map<String, Object> config, Consumer<String> onToken) {
        String provider = resolveProvider(model);

        List<Message> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userPrompt));

        StringBuilder fullResponse = new StringBuilder();

        try {
            ChatClient chatClient;

            if ("openai".equals(provider) || isOpenAiModel(model)) {
                chatClient = openAiChatClient;
            } else {
                chatClient = ollamaChatClient;
            }

            log.info("Spring AI streaming chat: model={}", model);

            Flux<String> flux = chatClient.prompt()
                    .messages(messages)
                    .options(getOptionsBuilder(model, config))
                    .stream()
                    .content();

            flux.subscribe(
                    text -> {
                        if (text != null && !text.isEmpty()) {
                            fullResponse.append(text);
                            onToken.accept(text);
                        }
                    },
                    error -> {
                        log.error("Streaming error: {}", error.getMessage());
                        onToken.accept("Error: " + error.getMessage());
                    }
            );

            Thread.sleep(500);
            return fullResponse.toString();
        } catch (Exception e) {
            log.error("Spring AI streaming error: {}", e.getMessage(), e);
            onToken.accept("Error: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            String result = ollamaChatClient.prompt()
                    .messages(new UserMessage("test"))
                    .options(OllamaChatOptions.builder()
                            .model("qwen2.5:0.5b")
                            .build())
                    .call()
                    .content();
            return result != null;
        } catch (Exception e) {
            log.warn("Spring AI Ollama not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getName() {
        return "spring-ai";
    }

    @Override
    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        models.addAll(listOllamaModels());
        models.addAll(listOpenAiModels());
        return models;
    }

    @Override
    public String getBaseUrl() {
        return "spring-ai";
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    private List<String> listOllamaModels() {
        return List.of("qwen2.5:0.5b", "llama3.2", "mistral", "qwen2.5", "deepseek-r1");
    }

    private List<String> listOpenAiModels() {
        return List.of("gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo", "o1", "o1-mini", "o3");
    }

    private String resolveProvider(String model) {
        if (model == null || model.isBlank()) return "ollama";
        String lower = model.toLowerCase();
        if (lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3")) {
            return "openai";
        }
        return "ollama";
    }

    private boolean isOpenAiModel(String model) {
        if (model == null) return false;
        String lower = model.toLowerCase();
        return lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3");
    }

    private double getTemperature(Map<String, Object> config) {
        if (config == null) return 0.7;
        Object temp = config.get("temperature");
        if (temp instanceof Number) {
            return ((Number) temp).doubleValue();
        }
        return 0.7;
    }

    private org.springframework.ai.chat.prompt.ChatOptions getOptionsBuilder(String model, Map<String, Object> config) {
        if (isOpenAiModel(model)) {
            return OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(getTemperature(config))
                    .build();
        } else {
            return OllamaChatOptions.builder()
                    .model(model)
                    .temperature(getTemperature(config))
                    .build();
        }
    }
}
