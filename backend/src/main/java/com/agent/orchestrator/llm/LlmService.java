package com.agent.orchestrator.llm;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.List;
import java.util.Map;

/**
 * Сервис маршрутизации LLM-запросов.
 * Выбирает провайдера на основе имени модели из узла.
 */
@Service
public class LlmService {

    private final Map<String, LlmProvider> providers;

    public LlmService(List<LlmProvider> providerList) {
        this.providers = new HashMap<>();
        for (LlmProvider provider : providerList) {
            providers.put(provider.getName(), provider);
        }
        System.out.println("🧠 LLM провайдеры: " + providers.keySet());
    }

    /**
     * Отправить запрос к LLM через соответствующий провайдер.
     *
     * @param model        имя модели или провайдера (например "ollama", "local", "gemma4:e2b")
     * @param systemPrompt системный промпт
     * @param userPrompt   пользовательский промпт
     * @param config       дополнительная конфигурация
     * @return ответ от LLM
     */
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        String providerName = resolveProvider(model);
        LlmProvider provider = providers.get(providerName);

        if (provider == null) {
            String error = "Провайдер LLM не найден: " + providerName + " (доступны: " + providers.keySet() + ")";
            System.err.println("❌ " + error);
            return error;
        }

        return provider.chat(model, systemPrompt, userPrompt, config);
    }

    /**
     * Определить провайдера по имени модели.
     * "local" и "ollama" → ollama, "openai" → openai и т.д.
     */
    private String resolveProvider(String model) {
        if (model == null || model.isBlank()) return "ollama";
        return switch (model.toLowerCase()) {
            case "local", "ollama" -> "ollama";
            default -> {
                // Если имя модели не совпадает с провайдером, пробуем ollama
                yield "ollama";
            }
        };
    }

    /**
     * Проверить доступность провайдера.
     */
    public boolean isProviderAvailable(String model) {
        String providerName = resolveProvider(model);
        LlmProvider provider = providers.get(providerName);
        return provider != null && provider.isAvailable();
    }

    /**
     * Получить информацию о всех провайдерах для настроек.
     */
    public List<Map<String, Object>> getProvidersInfo() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LlmProvider provider : providers.values()) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", provider.getName());
            info.put("available", provider.isAvailable());
            info.put("baseUrl", provider.getBaseUrl());
            info.put("models", provider.listModels());
            result.add(info);
        }
        return result;
    }

    /**
     * Список моделей провайдера.
     */
    public List<String> listModels(String providerName) {
        LlmProvider provider = providers.get(providerName);
        return provider != null ? provider.listModels() : List.of();
    }
}
