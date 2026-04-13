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
     * Stream tokens from LLM via callback. Returns the full response at the end.
     * Default implementation falls back to non-streaming chat.
     */
    default String streamingChat(String model, String systemPrompt, String userPrompt,
                                  Map<String, Object> config, java.util.function.Consumer<String> onToken) {
        String response = chat(model, systemPrompt, userPrompt, config);
        // Simulate streaming by sending the full response as one token
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
