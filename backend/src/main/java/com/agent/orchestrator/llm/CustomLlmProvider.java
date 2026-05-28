package com.agent.orchestrator.llm;

import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.repository.CustomLlmEndpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
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

import static com.agent.orchestrator.llm.LlmResponse.textOnly;

@Component
public class CustomLlmProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(CustomLlmProvider.class);
    private static final int TIMEOUT_SECONDS = 3600;

    private final CustomLlmEndpointRepository endpointRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CustomLlmProvider(CustomLlmEndpointRepository endpointRepository) {
        this.endpointRepository = endpointRepository;
        this.objectMapper = new ObjectMapper();
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
        List<CustomLlmEndpoint> endpoints = endpointRepository.findEnabled();
        if (endpoints.isEmpty()) {
            return textOnly("Error: No custom LLM endpoints configured");
        }

        CustomLlmEndpoint endpoint = endpoints.get(0);
        endpoint.setLastUsedAt(Instant.now());
        endpointRepository.save(endpoint);

        return chatWithOpenAiClient(endpoint, systemPrompt, userPrompt, usage);
    }

    @Override
    public LlmResponse streamingChat(String modelHint, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> tokenConsumer) {
        List<CustomLlmEndpoint> endpoints = endpointRepository.findEnabled();
        if (endpoints.isEmpty()) {
            tokenConsumer.accept("Error: No custom LLM endpoints configured");
            return textOnly("Error: No custom LLM endpoints configured");
        }

        CustomLlmEndpoint endpoint = endpoints.get(0);
        endpoint.setLastUsedAt(Instant.now());
        endpointRepository.save(endpoint);

        return streamingChatWithOpenAiClient(endpoint, systemPrompt, userPrompt, tokenConsumer);
    }

    /**
     * Non-streaming chat via OpenAiChatClient (supports reasoning extraction).
     */
    private LlmResponse chatWithOpenAiClient(CustomLlmEndpoint endpoint, String systemPrompt,
                                              String userPrompt, LlmUsage usage) {
        try {
            if ("bearer".equals(endpoint.getAuthType()) || endpoint.getAuthType() == null) {
                String key = endpoint.getApiKey() != null ? endpoint.getApiKey() : "";
                String model = endpoint.getModelName() != null ? endpoint.getModelName() : "default";
                return OpenAiChatClient.chat(key, endpoint.getBaseUrl(), model,
                        systemPrompt, userPrompt, usage, TIMEOUT_SECONDS);
            } else {
                return sendRawHttpRequest(endpoint, systemPrompt, userPrompt, usage);
            }
        } catch (Exception e) {
            log.error("Custom LLM chat error, falling back to raw HTTP", e);
            return sendRawHttpRequest(endpoint, systemPrompt, userPrompt, usage);
        }
    }

    /**
     * Raw HTTP fallback for api-key auth and error recovery (supports reasoning extraction).
     */
    @SuppressWarnings("unchecked")
    private LlmResponse sendRawHttpRequest(CustomLlmEndpoint endpoint, String systemPrompt,
                                            String userPrompt, LlmUsage usage) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", endpoint.getModelName());

            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));
            requestBody.put("messages", messages);
            requestBody.put("stream", false);

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

            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return OpenAiChatClient.parseResponse(response.body(), usage);
            } else {
                String error = "Custom LLM request failed: HTTP " + response.statusCode();
                log.error("{} - {}", error, truncate(response.body(), 500));
                return textOnly(error);
            }
        } catch (Exception e) {
            log.error("Custom LLM request failed", e);
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

            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

            StringBuilder fullResponse = new StringBuilder();
            StringBuilder reasoningBuffer = new StringBuilder();

            HttpResponse<InputStream> rawResponse = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (rawResponse.statusCode() == 200) {
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
                try { reader.join(120000); } catch (InterruptedException ignored) {}

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
        } catch (Exception e) {
            log.error("Custom LLM streaming request failed", e);
            String error = "Error: " + e.getMessage();
            tokenConsumer.accept(error);
            return textOnly(error);
        }
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

            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("Connection test failed", e);
            return false;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
