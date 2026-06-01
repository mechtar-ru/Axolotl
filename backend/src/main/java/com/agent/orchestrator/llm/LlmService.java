package com.agent.orchestrator.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.repository.CustomLlmEndpointRepository;
import com.agent.orchestrator.service.SettingsService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM request router. Selects provider by model name, caches provider status
 * and model lists with startup pre-fetch and Neo4j persistence.
 * Model lists are fetched dynamically from provider APIs at startup and on test.
 * When an API is unreachable, falls back to the last known list from Neo4j.
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private static final long CACHE_TTL_MINUTES = 5;

    private final Map<String, LlmProvider> providers;
    private final CustomLlmEndpointRepository customEndpointRepository;
    private final SettingsService settingsService;
    private final ConcurrentHashMap<String, CachedProviderInfo> providerCache = new ConcurrentHashMap<>();

    public LlmService(List<LlmProvider> providerList, CustomLlmEndpointRepository customEndpointRepository,
                      SettingsService settingsService) {
        this.providers = new HashMap<>();
        for (LlmProvider provider : providerList) {
            providers.put(provider.getName(), provider);
        }
        this.customEndpointRepository = customEndpointRepository;
        this.settingsService = settingsService;
        log.info("LLM providers: {}", providers.keySet());
    }

    /**
     * On startup: check all providers, fetch model lists from their APIs,
     * persist results to Neo4j. Falls back to Neo4j when an API is unreachable.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void preloadProviderCache() {
        log.info("Pre-loading provider status and model lists...");
        for (LlmProvider provider : providers.values()) {
            refreshBuiltInProvider(provider);
        }
        log.info("Provider cache pre-loaded: {} entries", providerCache.size());
    }

    /**
     * Refresh a single built-in provider: fetch from API, persist to Neo4j,
     * fall back to Neo4j on failure.
     */
    private void refreshBuiltInProvider(LlmProvider provider) {
        String name = provider.getName();
        try {
            boolean available = provider.isAvailable();
            List<String> models = List.of();
            if (available) {
                models = provider.listModels();
            }
            if (models != null && !models.isEmpty()) {
                // API returned models — persist to Neo4j
                settingsService.setModels(name, models);
            } else {
                // API returned nothing — try Neo4j fallback
                List<String> dbModels = settingsService.getModels(name);
                if (!dbModels.isEmpty()) {
                    log.info("  {} — using DB fallback ({} models)", name, dbModels.size());
                    models = dbModels;
                }
            }
            providerCache.put(name, new CachedProviderInfo(available, models));
            if (available) {
                log.info("  {} — available ({} models)", name, models.size());
            } else {
                log.info("  {} — unavailable", name);
            }
        } catch (Exception e) {
            log.warn("  {} — error checking: {}", name, e.getMessage());
            // Fall back to Neo4j
            List<String> dbModels = settingsService.getModels(name);
            providerCache.put(name, new CachedProviderInfo(false, dbModels));
        }
    }

    // --- Cached provider info ---

    private static class CachedProviderInfo {
        final boolean available;
        final List<String> models;
        final Instant lastCheckedAt;

        CachedProviderInfo(boolean available, List<String> models) {
            this.available = available;
            this.models = models != null ? models : List.of();
            this.lastCheckedAt = Instant.now();
        }

        boolean isStale() {
            return ChronoUnit.MINUTES.between(lastCheckedAt, Instant.now()) >= CACHE_TTL_MINUTES;
        }
    }

    /**
     * Force-refresh cache for one built-in provider. Persists to Neo4j on success.
     */
    public Map<String, Object> refreshProviderCache(String providerName) {
        LlmProvider provider = providers.get(providerName);
        if (provider == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("available", false);
            err.put("models", List.of());
            err.put("error", "Unknown provider: " + providerName);
            return err;
        }
        boolean available = provider.isAvailable();
        List<String> models = available ? provider.listModels() : List.of();
        if (!models.isEmpty()) {
            settingsService.setModels(providerName, models);
        } else {
            List<String> dbModels = settingsService.getModels(providerName);
            if (!dbModels.isEmpty()) {
                models = dbModels;
            }
        }
        providerCache.put(providerName, new CachedProviderInfo(available, models));
        Map<String, Object> result = new HashMap<>();
        result.put("available", available);
        result.put("models", models);
        return result;
    }

    // --- Core methods ---

    public LlmResponse chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        return chat(model, systemPrompt, userPrompt, config, null);
    }

    public LlmResponse streamingChat(String model, String systemPrompt, String userPrompt,
                                      Map<String, Object> config, java.util.function.Consumer<String> onToken) {
        return streamingChat(model, systemPrompt, userPrompt, config, onToken, null);
    }

    public LlmResponse chat(String model, String systemPrompt, String userPrompt,
                            Map<String, Object> config, LlmUsage usage) {
        // Build model chain: primary + fallbacks from config
        List<String> models = buildModelChain(model, config);

        Exception lastException = null;
        for (String m : models) {
            try {
                String providerName = resolveProvider(m);
                LlmProvider provider = providers.get(providerName);
                if (provider == null) {
                    String error = "Provider not found: " + providerName + " (available: " + providers.keySet() + ")";
                    log.error(error);
                    throw new RuntimeException(error);
                }
                String strippedModel = stripProviderPrefix(m);
                LlmResponse response = provider.chat(strippedModel, systemPrompt, userPrompt, config, usage);
                // Validate response — "Error:" prefix or blank text means the provider returned an error text, not a real response
                if (response == null || response.text() == null || response.text().isBlank()) {
                    log.warn("Model {} returned empty response; trying fallback if available", m);
                    lastException = new RuntimeException("Empty response from " + m);
                    continue;
                }
                if (response.text().startsWith("Error:")) {
                    log.warn("Model {} returned error: {}; trying fallback if available", m, response.text());
                    lastException = new RuntimeException(response.text());
                    continue;
                }
                if (m != null && !m.equals(model)) {
                    log.info("Fallback succeeded: primary={} used={}", model, m);
                }
                return response;
            } catch (Exception e) {
                if (m != null) {
                    log.warn("Model {} failed: {}; trying fallback if available", m, e.getMessage());
                } else {
                    log.warn("Model <null> failed: {}; trying fallback if available", e.getMessage());
                }
                lastException = e;
            }
        }
        throw new RuntimeException("All models exhausted — last error: "
                + (lastException != null ? lastException.getMessage() : "unknown"));
    }

    public LlmResponse streamingChat(String model, String systemPrompt, String userPrompt,
                                      Map<String, Object> config, java.util.function.Consumer<String> onToken,
                                      LlmUsage usage) {
        List<String> models = buildModelChain(model, config);

        Exception lastException = null;
        for (String m : models) {
            try {
                String providerName = resolveProvider(m);
                LlmProvider provider = providers.get(providerName);
                if (provider == null) {
                    String error = "Provider not found: " + providerName;
                    onToken.accept(error);
                    throw new RuntimeException(error);
                }
                String strippedModel = stripProviderPrefix(m);
                LlmResponse response = provider.streamingChat(strippedModel, systemPrompt, userPrompt, config, onToken, usage);
                // Validate response — "Error:" prefix or blank text means the provider returned an error text
                if (response == null || response.text() == null || response.text().isBlank()) {
                    log.warn("Streaming model {} returned empty response; trying fallback", m);
                    lastException = new RuntimeException("Empty response from " + m);
                    continue;
                }
                if (response.text().startsWith("Error:")) {
                    log.warn("Streaming model {} returned error: {}; trying fallback", m, response.text());
                    lastException = new RuntimeException(response.text());
                    continue;
                }
                if (m != null && !m.equals(model)) {
                    log.info("Fallback succeeded: primary={} used={}", model, m);
                }
                return response;
            } catch (Exception e) {
                if (m != null) {
                    log.warn("Streaming model {} failed: {}; trying fallback", m, e.getMessage());
                } else {
                    log.warn("Streaming model <null> failed: {}; trying fallback", e.getMessage());
                }
                lastException = e;
            }
        }
        throw new RuntimeException("All models exhausted — last error: "
                + (lastException != null ? lastException.getMessage() : "unknown"));
    }

    /**
     * Build the ordered model chain: [primary] + [fallbackModels from config].
     */
    @SuppressWarnings("unchecked")
    List<String> buildModelChain(String primaryModel, Map<String, Object> config) {
        List<String> models = new ArrayList<>();
        models.add(primaryModel);
        if (config != null && config.get("fallbackModels") instanceof List) {
            List<String> fallbacks = (List<String>) config.get("fallbackModels");
            for (String fb : fallbacks) {
                if (fb != null && !fb.isBlank() && !models.contains(fb)) {
                    models.add(fb);
                }
            }
        }
        return models;
    }

    private String stripProviderPrefix(String model) {
        if (model == null || model.isBlank()) return model;
        // Only strip if there's a colon that creates provider:actualModel format
        int colon = model.indexOf(':');
        if (colon > 0 && colon < model.length() - 1) {
            String prefix = model.substring(0, colon).toLowerCase();
            // Only strip if the prefix matches a known provider
            if (providers.containsKey(prefix)) {
                return model.substring(colon + 1);
            }
        }
        return model;
    }

    private String resolveProvider(String model) {
        if (model == null || model.isBlank()) return "ollama";
        String lower = model.toLowerCase();
        if (providers.containsKey(lower)) return lower;
        if (lower.startsWith("@cf/")) return "custom";
        // Check if model has provider:model format and the prefix is a registered provider or custom endpoint
        int colonIdx = lower.indexOf(':');
        if (colonIdx > 0) {
            String prefix = lower.substring(0, colonIdx);
            if (providers.containsKey(prefix)) return prefix;
            // Custom endpoints use fixed provider name "custom" but have their own endpoint name as prefix
            if (customEndpointRepository.findEnabled().stream()
                    .anyMatch(e -> e.getName().equalsIgnoreCase(prefix))) return "custom";
        }
        String stripped = stripEndpointPrefix(lower);
        return switch (stripped) {
            case "local" -> "ollama";
            case "gpt" -> "openai";
            case "claude" -> "anthropic";
            case "zen", "opencode" -> "zen";
            default -> {
                if (stripped.startsWith("gpt-") || stripped.startsWith("o1-") || stripped.startsWith("o3-")) yield "openai";
                if (stripped.startsWith("claude-")) yield "anthropic";
                if (stripped.startsWith("deepseek-v4")) yield "zen";
                if (stripped.startsWith("deepseek-")) yield "deepseek";
                if (stripped.startsWith("llama") || stripped.startsWith("gemma") || stripped.startsWith("mistral") || stripped.startsWith("qwen")) yield "ollama";
                if (stripped.endsWith("-pickle") || stripped.startsWith("minimax-") || stripped.startsWith("kimi-") ||
                    stripped.startsWith("glm-") || stripped.startsWith("qwen3.") || stripped.startsWith("trinity-") ||
                    stripped.startsWith("hy3-") || stripped.startsWith("ling-") || stripped.startsWith("nemotron-")) yield "zen";
                yield "ollama";
            }
        };
    }

    private String stripEndpointPrefix(String model) {
        int colon = model.indexOf(':');
        return colon > 0 ? model.substring(colon + 1) : model;
    }

    public boolean isProviderAvailable(String model) {
        String providerName = resolveProvider(model);
        LlmProvider provider = providers.get(providerName);
        return provider != null && provider.isAvailable();
    }

    /**
     * Get info for all providers (built-in + custom endpoints).
     * Uses cache if fresh, otherwise live-fetches with DB persistence.
     */
    public List<Map<String, Object>> getProvidersInfo() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LlmProvider provider : providers.values()) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", provider.getName());

            CachedProviderInfo cached = providerCache.get(provider.getName());
            if (cached != null && !cached.isStale()) {
                info.put("available", cached.available);
                info.put("models", cached.models);
            } else {
                boolean available = provider.isAvailable();
                List<String> models = available ? provider.listModels() : List.of();
                if (!models.isEmpty()) {
                    settingsService.setModels(provider.getName(), models);
                }
                providerCache.put(provider.getName(), new CachedProviderInfo(available, models));
                info.put("available", available);
                info.put("models", models);
            }

            info.put("baseUrl", provider.getBaseUrl());
            info.put("custom", false);
            // Attach persisted defaultModel from settings
            String storedDefault = settingsService.getDefaultModel(provider.getName());
            if (storedDefault != null) {
                info.put("defaultModel", storedDefault);
            }
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

    public List<String> listModels(String providerName) {
        LlmProvider provider = providers.get(providerName);
        if (provider == null) return List.of();

        CachedProviderInfo cached = providerCache.get(providerName);
        if (cached != null && !cached.isStale() && cached.available) {
            return cached.models;
        }
        // Try live fetch from provider first. Some unit tests stub provider.listModels
        // but may not stub isAvailable(), so call listModels() directly and fall back
        // to DB if the provider returns empty.
        List<String> models;
        try {
            models = provider.listModels();
        } catch (Exception e) {
            models = List.of();
        }
        boolean available = !models.isEmpty() || provider.isAvailable();
        if (!models.isEmpty()) {
            settingsService.setModels(providerName, models);
        } else {
            List<String> dbModels = settingsService.getModels(providerName);
            if (!dbModels.isEmpty()) {
                models = dbModels;
            }
        }
        providerCache.put(providerName, new CachedProviderInfo(available, models));
        return models;
    }

    /**
     * Health check for the Settings test button.
     * Pings the provider live, updates cache, persists models to Neo4j.
     */
    public Map<String, Object> checkProviderHealth(String providerName) {
        Map<String, Object> result = new HashMap<>();
        result.put("provider", providerName);

        LlmProvider provider = providers.get(providerName);
        if (provider == null) {
            result.put("available", false);
            result.put("models", List.of());
            result.put("error", "Unknown provider");
            return result;
        }

        boolean available;
        List<String> models = List.of();
        try {
            available = provider.isAvailable();
            if (available) {
                models = provider.listModels();
            }
        } catch (Exception e) {
            available = false;
            result.put("error", e.getMessage());
        }

        // Persist to Neo4j if API returned models, otherwise fall back
        if (!models.isEmpty()) {
            settingsService.setModels(providerName, models);
        } else {
            List<String> dbModels = settingsService.getModels(providerName);
            if (!dbModels.isEmpty()) {
                models = dbModels;
            }
        }

        providerCache.put(providerName, new CachedProviderInfo(available, models));

        result.put("available", available);
        result.put("models", models);
        return result;
    }
}
