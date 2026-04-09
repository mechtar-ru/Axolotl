package com.agent.orchestrator.llm;

import java.util.List;
import java.util.Map;

/**
 * Интерфейс провайдера LLM.
 * Каждая реализация — конкретный провайдер (Ollama, OpenAI, Anthropic и т.д.)
 */
public interface LlmProvider {

    /**
     * Отправить запрос к LLM и получить ответ.
     */
    String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config);

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
