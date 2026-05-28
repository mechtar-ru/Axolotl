package com.agent.orchestrator.llm;

import java.util.List;
import java.util.Map;

/**
 * Интерфейс провайдера LLM.
 * Каждая реализация — конкретный провайдер (Ollama, OpenAI, Anthropic и т.д.)
 */
public interface LlmProvider {

    /**
     * Отправить запрос к LLM и получить полный ответ.
     */
    String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config);

    /**
     * Отправить запрос к LLM с отслеживанием токенов.
     * По умолчанию делегирует в chat() без отслеживания.
     */
    default String chat(String model, String systemPrompt, String userPrompt,
                        Map<String, Object> config, LlmUsage usage) {
        return chat(model, systemPrompt, userPrompt, config);
    }

    /**
     * Stream tokens from LLM via callback. Returns the full response at the end.
     * Default implementation falls back to non-streaming chat.
     */
    default String streamingChat(String model, String systemPrompt, String userPrompt,
                                  Map<String, Object> config, java.util.function.Consumer<String> onToken) {
        return streamingChat(model, systemPrompt, userPrompt, config, onToken, null);
    }

    /**
     * Stream tokens with tracking. Default falls back to non-streaming with usage.
     */
    default String streamingChat(String model, String systemPrompt, String userPrompt,
                                  Map<String, Object> config, java.util.function.Consumer<String> onToken,
                                  LlmUsage usage) {
        String response = chat(model, systemPrompt, userPrompt, config, usage);
        onToken.accept(response);
        return response;
    }

    /**
     * Whether this provider supports real token streaming.
     */
    default boolean supportsStreaming() {
        return false;
    }

    /**
     * Проверить доступность провайдера.
     */
    boolean isAvailable();

    /**
     * Имя провайдера (например "ollama", "openai").
     */
    String getName();

    /**
     * Список доступных моделей.
     */
    List<String> listModels();

    /**
     * URL провайдера (для отображения в настройках).
     */
    String getBaseUrl();
}
