package com.agent.orchestrator.llm;

import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.repository.CustomLlmEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class CustomLlmProvider implements LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(CustomLlmProvider.class);
    private static final int TIMEOUT_SECONDS = 300;
    private static final int MAX_RETRIES = 2;
    private final CustomLlmEndpointRepository endpointRepository;
    private final HttpClient httpClient;

    public CustomLlmProvider(CustomLlmEndpointRepository endpointRepository) {
        this.endpointRepository = endpointRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
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
    public String chat(String modelHint, String systemPrompt, String userPrompt, Map<String, Object> options) {
        List<CustomLlmEndpoint> endpoints = endpointRepository.findEnabled();
        if (endpoints.isEmpty()) {
            return "Error: No custom LLM endpoints configured";
        }

        CustomLlmEndpoint endpoint = endpoints.get(0);
        return sendRequest(endpoint, systemPrompt, userPrompt);
    }

    @Override
    public String streamingChat(String modelHint, String systemPrompt, String userPrompt, Map<String, Object> options,
                               java.util.function.Consumer<String> tokenConsumer) {
        List<CustomLlmEndpoint> endpoints = endpointRepository.findEnabled();
        if (endpoints.isEmpty()) {
            tokenConsumer.accept("Error: No custom LLM endpoints configured");
            return "Error: No custom LLM endpoints configured";
        }

        CustomLlmEndpoint endpoint = endpoints.get(0);
        endpoint.setLastUsedAt(Instant.now());
        endpointRepository.save(endpoint);

        return sendStreamingRequest(endpoint, systemPrompt, userPrompt, tokenConsumer);
    }

    private String sendRequest(CustomLlmEndpoint endpoint, String systemPrompt, String userPrompt) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
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

                String jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(requestBody);

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint.getBaseUrl() + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS));

                if ("bearer".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                    builder.header("Authorization", "Bearer " + endpoint.getApiKey());
                } else if ("api-key".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                    builder.header("X-API-Key", endpoint.getApiKey());
                }

                if (endpoint.getHeaders() != null) {
                    for (var entry : endpoint.getHeaders().entrySet()) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }

                HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
                    String content = node.at("/choices/0/message/content").asText();
                    if (content == null || content.isBlank()) {
                        content = node.at("/choices/0/message/reasoning_content").asText();
                    }
                    return content != null ? content : "";
                } else {
                    int code = response.statusCode();
                    if ((code == 408 || code >= 500) && attempt < MAX_RETRIES) {
                        log.warn("Custom LLM server error {} (attempt {}/{}), retrying", code, attempt, MAX_RETRIES);
                        continue;
                    }
                    log.error("Custom LLM request failed: {} - {}", code, response.body());
                    return "Error: HTTP " + code;
                }
            } catch (java.net.http.HttpTimeoutException e) {
                log.warn("Custom LLM timeout (attempt {}/{}): {}s", attempt, MAX_RETRIES, TIMEOUT_SECONDS);
                if (attempt == MAX_RETRIES) {
                    return "Error: request timed out after " + attempt + " attempts";
                }
            } catch (java.io.IOException e) {
                log.warn("Custom LLM network error (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    return "Error: " + e.getMessage() + " (" + attempt + " attempts)";
                }
            } catch (Exception e) {
                log.error("Custom LLM request failed", e);
                return "Error: " + e.getMessage();
            }
        }
        return "Error: all retries exhausted";
    }

    private String sendStreamingRequest(CustomLlmEndpoint endpoint, String systemPrompt, String userPrompt,
                                       java.util.function.Consumer<String> tokenConsumer) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
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

                String jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(requestBody);

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint.getBaseUrl() + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS));

                if ("bearer".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                    builder.header("Authorization", "Bearer " + endpoint.getApiKey());
                } else if ("api-key".equals(endpoint.getAuthType()) && endpoint.getApiKey() != null) {
                    builder.header("X-API-Key", endpoint.getApiKey());
                }

                if (endpoint.getHeaders() != null) {
                    for (var entry : endpoint.getHeaders().entrySet()) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }

                HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

                StringBuilder fullResponse = new StringBuilder();
                HttpResponse<java.io.InputStream> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() == 200) {
                    java.io.InputStream in = response.body();

                    Thread reader = new Thread(() -> {
                        try (java.io.BufferedReader reader2 = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
                            String line;
                            while ((line = reader2.readLine()) != null) {
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);
                                    if (!data.equals("[DONE]")) {
                                        try {
                                            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(data);
                                            String content = node.at("/choices/0/delta/content").asText("");
                                            if (content.isEmpty()) {
                                                content = node.at("/choices/0/delta/reasoning_content").asText("");
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
                    reader.join(TIMEOUT_SECONDS * 1000L);
                    return fullResponse.toString();
                } else {
                    String error = "Error: HTTP " + response.statusCode();
                    tokenConsumer.accept(error);
                    return error;
                }
            } catch (java.net.http.HttpTimeoutException e) {
                log.warn("Custom LLM streaming timeout (attempt {}/{}): {}s", attempt, MAX_RETRIES, TIMEOUT_SECONDS);
                if (attempt == MAX_RETRIES) {
                    String error = "Error: streaming request timed out after " + (TIMEOUT_SECONDS * attempt) + "s (" + MAX_RETRIES + " attempts)";
                    tokenConsumer.accept(error);
                    return error;
                }
            } catch (Exception e) {
                log.error("Custom LLM streaming request failed (attempt {}/{})", attempt, MAX_RETRIES, e);
                if (attempt == MAX_RETRIES) {
                    String error = "Error: " + e.getMessage();
                    tokenConsumer.accept(error);
                    return error;
                }
            }
        }
        return "Error: all streaming retries exhausted";
    }

    public boolean testConnection(CustomLlmEndpoint endpoint) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", endpoint.getModelName());
            requestBody.put("messages", List.of(Map.of("role", "user", "content", "ping")));
            requestBody.put("max_tokens", 50);

            String jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(requestBody);

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
}
