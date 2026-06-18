package com.agent.orchestrator.llm;

import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.repository.CustomLlmEndpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agent.orchestrator.llm.LlmResponse.textOnly;

@Component
public class CustomLlmProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(CustomLlmProvider.class);
    private static final int TIMEOUT_SECONDS = 120;
    private static final String OPENROUTER_REFERER = "https://axolotl.app";
    private static final String OPENROUTER_TITLE = "Axolotl";

    private final CustomLlmEndpointRepository endpointRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CustomLlmProvider(CustomLlmEndpointRepository endpointRepository, ObjectMapper objectMapper) {
        this.endpointRepository = endpointRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String getName() {
        return "custom";
    }

    @Override
    public String getBaseUrl() {
        var enabled = endpointRepository.findEnabled();
        return enabled.isEmpty() ? null : enabled.get(0).getBaseUrl();
    }

    @Override
    public boolean isAvailable() {
        return !endpointRepository.findEnabled().isEmpty();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        for (CustomLlmEndpoint endpoint : endpointRepository.findEnabled()) {
            if (endpoint.getModelName() != null && !endpoint.getModelName().isBlank()) {
                models.add(endpoint.getName() + ":" + endpoint.getModelName());
            }
        }
        return models;
    }

    @Override
    public LlmResponse chat(String modelHint, String systemPrompt, String userPrompt, Map<String, Object> config) {
        return chat(modelHint, systemPrompt, userPrompt, config, null);
    }

    @Override
    public LlmResponse chat(String modelHint, String systemPrompt, String userPrompt,
                       Map<String, Object> config, LlmUsage usage) {
        CustomLlmEndpoint endpoint = resolveEndpoint(modelHint);
        if (endpoint == null) {
            return textOnly("Error: No custom LLM endpoint found for model: " + modelHint);
        }
        endpoint.setLastUsedAt(Instant.now());
        endpointRepository.save(endpoint);

        // Extract structured tools from config if present (passed from AgentNodeStrategy)
        List<Map<String, Object>> tools = extractToolsFromConfig(config);
        return chatWithOpenAiClient(endpoint, systemPrompt, userPrompt, usage, tools);
    }

    @Override
    public LlmResponse streamingChat(String modelHint, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> tokenConsumer) {
        CustomLlmEndpoint endpoint = resolveEndpoint(modelHint);
        if (endpoint == null) {
            tokenConsumer.accept("Error: No custom LLM endpoint found for model: " + modelHint);
            return textOnly("Error: No custom LLM endpoint found for model: " + modelHint);
        }
        endpoint.setLastUsedAt(Instant.now());
        endpointRepository.save(endpoint);

        List<Map<String, Object>> tools = extractToolsFromConfig(config);
        return streamingChatWithOpenAiClient(endpoint, systemPrompt, userPrompt, tokenConsumer, tools);
    }

    /**
     * Resolve the custom endpoint from a model hint.
     * Model hint format: "endpointName:actualModel" (e.g. "openrouter:nvidia/nemotron-3-...").
     * Also supports "@cf/endpointName" legacy format.
     * Falls back to the first enabled endpoint if no match.
     */
    private CustomLlmEndpoint resolveEndpoint(String modelHint) {
        List<CustomLlmEndpoint> endpoints = endpointRepository.findEnabled();
        if (endpoints.isEmpty()) return null;

        if (modelHint != null && !modelHint.isBlank()) {
            String prefix = null;

            // Try "endpointName:model" format
            int colon = modelHint.indexOf(':');
            if (colon > 0) {
                prefix = modelHint.substring(0, colon).toLowerCase();
            }

            // Try "@cf/endpointName" legacy format
            if (prefix == null && modelHint.toLowerCase().startsWith("@cf/")) {
                prefix = modelHint.substring(4).toLowerCase(); // drop "@cf/"
            }

            if (prefix != null) {
                for (CustomLlmEndpoint ep : endpoints) {
                    if (ep.getName().equalsIgnoreCase(prefix)) {
                        return ep;
                    }
                }
            }
        }

        // Fallback: first enabled endpoint
        return endpoints.get(0);
    }

    /**
     * Extract structured tools from config map (passed from AgentNodeStrategy via LlmService).
     * Config key "_tools" contains List<Map<String,Object>> with tool definitions.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractToolsFromConfig(Map<String, Object> config) {
        if (config == null) return null;
        Object raw = config.get("_tools");
        if (raw instanceof List) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (Object item : (List<Object>) raw) {
                if (item instanceof Map) {
                    tools.add(OpenAiChatClient.toolToOpenAiFormat((Map<String, Object>) item));
                }
            }
            return tools.isEmpty() ? null : tools;
        }
        return null;
    }

    /**
     * Non-streaming chat via OpenAiChatClient (supports reasoning extraction).
     */
    private LlmResponse chatWithOpenAiClient(CustomLlmEndpoint endpoint, String systemPrompt,
                                              String userPrompt, LlmUsage usage) {
        return chatWithOpenAiClient(endpoint, systemPrompt, userPrompt, usage, null);
    }

    /**
     * Non-streaming chat via OpenAiChatClient with optional structured tools.
     */
    private LlmResponse chatWithOpenAiClient(CustomLlmEndpoint endpoint, String systemPrompt,
                                              String userPrompt, LlmUsage usage,
                                              List<Map<String, Object>> tools) {
        try {
            if ("bearer".equals(endpoint.getAuthType()) || endpoint.getAuthType() == null) {
                String key = endpoint.getApiKey() != null ? endpoint.getApiKey() : "";
                String model = endpoint.getModelName() != null ? endpoint.getModelName() : "default";
                return OpenAiChatClient.chat(key, endpoint.getBaseUrl(), model,
                        systemPrompt, userPrompt, usage, TIMEOUT_SECONDS,
                        HttpClient.Version.HTTP_1_1, tools);
            } else {
                return sendRawHttpRequest(endpoint, systemPrompt, userPrompt, usage, tools);
            }
        } catch (Exception e) {
            log.error("Custom LLM chat error, falling back to raw HTTP", e);
            return sendRawHttpRequest(endpoint, systemPrompt, userPrompt, usage, tools);
        }
    }

    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    /**
     * Raw HTTP fallback for api-key auth and error recovery (supports reasoning extraction).
     * Delegates to retry-capable implementation.
     */
    private LlmResponse sendRawHttpRequest(CustomLlmEndpoint endpoint, String systemPrompt,
                                            String userPrompt, LlmUsage usage) {
        return sendRawHttpRequestWithRetry(endpoint, systemPrompt, userPrompt, usage, 0, null);
    }

    private LlmResponse sendRawHttpRequest(CustomLlmEndpoint endpoint, String systemPrompt,
                                            String userPrompt, LlmUsage usage,
                                            List<Map<String, Object>> tools) {
        return sendRawHttpRequestWithRetry(endpoint, systemPrompt, userPrompt, usage, 0, tools);
    }

    /**
     * Raw HTTP with rate-limit retry (max {@link #MAX_RATE_LIMIT_RETRIES} attempts).
     */
    @SuppressWarnings("unchecked")
    private LlmResponse sendRawHttpRequestWithRetry(CustomLlmEndpoint endpoint, String systemPrompt,
                                                     String userPrompt, LlmUsage usage,
                                                     int attempt) {
        return sendRawHttpRequestWithRetry(endpoint, systemPrompt, userPrompt, usage, attempt, null);
    }

    @SuppressWarnings("unchecked")
    private LlmResponse sendRawHttpRequestWithRetry(CustomLlmEndpoint endpoint, String systemPrompt,
                                                     String userPrompt, LlmUsage usage,
                                                     int attempt,
                                                     List<Map<String, Object>> tools) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", endpoint.getModelName());
            requestBody.put("max_tokens", 16384);

            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));
            requestBody.put("messages", messages);
            requestBody.put("stream", false);

            // Add structured tools if provided
            if (tools != null && !tools.isEmpty()) {
                requestBody.put("tools", tools);
            }

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.getBaseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120));

            if ("bearer".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                builder.header("Authorization", "Bearer " + endpoint.getApiKey());
            } else if ("api-key".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                builder.header("X-API-Key", endpoint.getApiKey());
            }

            // OpenRouter requires HTTP-Referer and X-Title headers
            addOpenRouterHeadersIfNeeded(builder, endpoint.getBaseUrl());

            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                if (attempt >= MAX_RATE_LIMIT_RETRIES) {
                    log.error("Rate limited after {} attempts, giving up", attempt);
                    return textOnly("Error: Rate limited after " + MAX_RATE_LIMIT_RETRIES + " retries");
                }
                String retryAfter = response.headers().firstValue("Retry-After").orElse("30");
                int waitSeconds;
                try {
                    waitSeconds = Integer.parseInt(retryAfter);
                } catch (NumberFormatException e) {
                    waitSeconds = 30;
                }
                waitSeconds = Math.min(waitSeconds, 60); // cap at 60s
                log.warn("Rate limited (attempt {}/{}), waiting {}s before retry",
                        attempt + 1, MAX_RATE_LIMIT_RETRIES, waitSeconds);
                try {
                    Thread.sleep(waitSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return textOnly("Error: Interrupted during rate-limit wait");
                }
                return sendRawHttpRequestWithRetry(endpoint, systemPrompt, userPrompt, usage, attempt + 1, tools);
            }

            if (response.statusCode() == 200) {
                LlmResponse parsed = OpenAiChatClient.parseResponse(response.body(), usage);
                // Detect empty response with HTTP 200 (e.g., OpenRouter rate limit)
                if (parsed.text() == null || parsed.text().isBlank()) {
                    throw new RuntimeException("Empty response from " + endpoint.getName()
                        + " (model: " + endpoint.getModelName() + ") — possible rate limit");
                }
                return parsed;
            } else {
                String error = "Custom LLM request failed: HTTP " + response.statusCode();
                log.error("{} - {}", error, truncate(response.body(), 500));
                return textOnly(error);
            }
        } catch (Exception e) {
            if (attempt < MAX_RATE_LIMIT_RETRIES) {
                log.warn("Custom LLM request failed (attempt {}), retrying: {}", attempt + 1, e.getMessage());
                try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return sendRawHttpRequestWithRetry(endpoint, systemPrompt, userPrompt, usage, attempt + 1, tools);
            }
            log.error("Custom LLM request failed after {} attempts", attempt, e);
            return textOnly("Error: " + e.getMessage());
        }
    }

    /**
     * Streaming chat — uses raw HTTP SSE for both bearer and api-key auth.
     */
    @SuppressWarnings("unchecked")
    private LlmResponse streamingChatWithOpenAiClient(CustomLlmEndpoint endpoint,
                                                       String systemPrompt, String userPrompt,
                                                       Consumer<String> tokenConsumer) {
        return streamingChatWithOpenAiClient(endpoint, systemPrompt, userPrompt, tokenConsumer, null);
    }

    @SuppressWarnings("unchecked")
    private LlmResponse streamingChatWithOpenAiClient(CustomLlmEndpoint endpoint,
                                                        String systemPrompt, String userPrompt,
                                                        Consumer<String> tokenConsumer,
                                                        List<Map<String, Object>> tools) {
        int maxRetries = 2;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", endpoint.getModelName());

                List<Map<String, String>> messages = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    messages.add(Map.of("role", "system", "content", systemPrompt));
                }
                messages.add(Map.of("role", "user", "content", userPrompt));
                requestBody.put("messages", messages);
                requestBody.put("stream", true);

                if (tools != null && !tools.isEmpty()) {
                    requestBody.put("tools", tools);
                }

                String jsonBody = objectMapper.writeValueAsString(requestBody);

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint.getBaseUrl() + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(120));

                if ("bearer".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                    builder.header("Authorization", "Bearer " + endpoint.getApiKey());
                } else if ("api-key".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                    builder.header("X-API-Key", endpoint.getApiKey());
                }

                // OpenRouter requires HTTP-Referer and X-Title headers
                addOpenRouterHeadersIfNeeded(builder, endpoint.getBaseUrl());

                HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

                StringBuilder fullResponse = new StringBuilder();
                StringBuilder reasoningBuffer = new StringBuilder();

                HttpResponse<InputStream> rawResponse = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                int sc = rawResponse.statusCode();
                if (sc == 429 || sc >= 500) {
                    if (attempt < maxRetries) {
                        Thread.sleep(1000 * (attempt + 1));
                        continue;
                    }
                    String error = "Error: HTTP " + sc;
                    tokenConsumer.accept(error);
                    return textOnly(error);
                }

                if (sc == 200) {
                InputStream in = rawResponse.body();
                Thread reader = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6);
                                if (!data.equals("[DONE]")) {
                                    try {
                                        var node = objectMapper.readTree(data);
                                        // reasoning_content comes before content in SSE
                                        String reasoning = node.at("/choices/0/delta/reasoning_content").asText("");
                                        String content = node.at("/choices/0/delta/content").asText("");

                                        if (!reasoning.isEmpty()) {
                                            reasoningBuffer.append(reasoning);
                                        }
                                        if (!content.isEmpty()) {
                                            fullResponse.append(content);
                                            tokenConsumer.accept(content);
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("Streaming read error", e);
                    }
                });
                reader.start();
                try { reader.join(120000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                String reasoning = reasoningBuffer.length() > 0 ? reasoningBuffer.toString() : null;
                String text = fullResponse.toString();

                if (reasoning != null && !reasoning.isBlank()) {
                    return new LlmResponse(text, reasoning);
                }
                return textOnly(text);
            } else {
                String error = "Error: HTTP " + rawResponse.statusCode();
                tokenConsumer.accept(error);
                return textOnly(error);
            }
            } catch (IOException e) {
                if (attempt < maxRetries && e.getMessage() != null &&
                        (e.getMessage().contains("429") || e.getMessage().contains("5"))) {
                    try { Thread.sleep(1000 * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                log.error("Custom LLM streaming request failed", e);
                String error = "Error: " + e.getMessage();
                tokenConsumer.accept(error);
                return textOnly(error);
            } catch (Exception e) {
                log.error("Custom LLM streaming request failed", e);
                String error = "Error: " + e.getMessage();
                tokenConsumer.accept(error);
                return textOnly(error);
            }
        }
        String error = "Error: streaming request failed after " + (maxRetries + 1) + " attempts";
        tokenConsumer.accept(error);
        return textOnly(error);
    }

    // Kept as raw HTTP — lightweight health check, no need for LangChain4j overhead
    @SuppressWarnings("unchecked")
    public boolean testConnection(CustomLlmEndpoint endpoint) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", endpoint.getModelName());
            requestBody.put("messages", List.of(Map.of("role", "user", "content", "ping")));
            requestBody.put("max_tokens", 50);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.getBaseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10));

            if ("bearer".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                builder.header("Authorization", "Bearer " + endpoint.getApiKey());
            } else if ("api-key".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                builder.header("X-API-Key", endpoint.getApiKey());
            }

            // OpenRouter requires HTTP-Referer and X-Title headers
            addOpenRouterHeadersIfNeeded(builder, endpoint.getBaseUrl());

            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("Connection test failed", e);
            return false;
        }
    }

    /**
     * If the baseUrl contains "openrouter.ai", add the required HTTP-Referer and X-Title headers.
     */
    private static void addOpenRouterHeadersIfNeeded(HttpRequest.Builder builder, String baseUrl) {
        if (baseUrl != null && baseUrl.contains("openrouter.ai")) {
            builder.header("HTTP-Referer", OPENROUTER_REFERER);
            builder.header("X-Title", OPENROUTER_TITLE);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
