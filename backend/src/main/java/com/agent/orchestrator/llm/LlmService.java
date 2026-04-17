package com.agent.orchestrator.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.repository.CustomLlmEndpointRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис маршрутизации LLM-запросов.
 * Выбирает провайдера на основе имени модели из узла.
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final Map<String, LlmProvider> providers;
    private final CustomLlmEndpointRepository customEndpointRepository;

    public LlmService(List<LlmProvider> providerList, CustomLlmEndpointRepository customEndpointRepository) {
        this.providers = new HashMap<>();
        for (LlmProvider provider : providerList) {
            providers.put(provider.getName(), provider);
        }
        this.customEndpointRepository = customEndpointRepository;
        log.info("LLM провайдеры: {}", providers.keySet());
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
            log.error(error);
            return error;
        }

        return provider.chat(model, systemPrompt, userPrompt, config);
    }

    /**
     * Stream LLM response via token callback.
     */
    public String streamingChat(String model, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, java.util.function.Consumer<String> onToken) {
        String providerName = resolveProvider(model);
        LlmProvider provider = providers.get(providerName);

        if (provider == null) {
            String error = "Провайдер LLM не найден: " + providerName;
            onToken.accept(error);
            return error;
        }

        return provider.streamingChat(model, systemPrompt, userPrompt, config, onToken);
    }

    /**
     * Определить провайдера по имени модели.
     * "local"/"ollama" → ollama, "gpt-*" → openai, "claude-*" → anthropic,
     * "deepseek-*" → deepseek, exact provider name match, fallback → ollama.
     */
    private String resolveProvider(String model) {
        if (model == null || model.isBlank()) return "ollama";
        String lower = model.toLowerCase();
        // Direct provider name
        if (providers.containsKey(lower)) return lower;
        return switch (lower) {
            case "local" -> "ollama";
            case "gpt" -> "openai";
            case "claude" -> "anthropic";
            default -> {
                // Match by model name prefix
                if (lower.startsWith("gpt-") || lower.startsWith("o1-") || lower.startsWith("o3-")) yield "openai";
                if (lower.startsWith("claude-")) yield "anthropic";
                if (lower.startsWith("deepseek-")) yield "deepseek";
                if (lower.startsWith("llama") || lower.startsWith("gemma") || lower.startsWith("mistral") || lower.startsWith("qwen")) yield "ollama";
                // Fallback to ollama
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
            info.put("custom", false);
            result.add(info);
        }
        for (CustomLlmEndpoint ep : customEndpointRepository.findAll()) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", ep.getName());
            info.put("available", ep.isEnabled() && ep.getApiKey() != null && !ep.getApiKey().isBlank());
            info.put("baseUrl", ep.getBaseUrl());
            info.put("models", ep.getModelName() != null ? List.of(ep.getModelName()) : List.of());
            info.put("defaultModel", ep.getModelName());
            info.put("custom", true);
            info.put("id", ep.getId());
            info.put("authType", ep.getAuthType());
            info.put("enabled", ep.isEnabled());
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
