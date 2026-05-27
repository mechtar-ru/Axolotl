package com.agent.orchestrator.llm;

import com.agent.orchestrator.model.CustomLlmEndpoint;
import com.agent.orchestrator.repository.CustomLlmEndpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
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
    public String chat(String modelHint, String systemPrompt, String userPrompt, Map<String, Object> config) {
        List<CustomLlmEndpoint> endpoints = endpointRepository.findEnabled();
        if (endpoints.isEmpty()) {
            return "Error: No custom LLM endpoints configured";
        }

        CustomLlmEndpoint endpoint = endpoints.get(0);
        endpoint.setLastUsedAt(Instant.now());
        endpointRepository.save(endpoint);

        // Use LangChain4j for Bearer auth (most common), raw HTTP for api-key auth
        if (!"api-key".equals(endpoint.getAuthType())) {
            return chatWithLangChain4j(endpoint, systemPrompt, userPrompt);
        } else {
            return sendRequest(endpoint, systemPrompt, userPrompt);
        }
    }

    @Override
    public String streamingChat(String modelHint, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> tokenConsumer) {
        List<CustomLlmEndpoint> endpoints = endpointRepository.findEnabled();
        if (endpoints.isEmpty()) {
            tokenConsumer.accept("Error: No custom LLM endpoints configured");
            return "Error: No custom LLM endpoints configured";
        }

        CustomLlmEndpoint endpoint = endpoints.get(0);
        endpoint.setLastUsedAt(Instant.now());
        endpointRepository.save(endpoint);

        // Use LangChain4j for Bearer auth, raw HTTP for api-key auth
        if (!"api-key".equals(endpoint.getAuthType())) {
            return streamingChatWithLangChain4j(endpoint, systemPrompt, userPrompt, tokenConsumer);
        } else {
            return sendStreamingRequest(endpoint, systemPrompt, userPrompt, tokenConsumer);
        }
    }

    private String chatWithLangChain4j(CustomLlmEndpoint endpoint, String systemPrompt, String userPrompt) {
        try {
            String effectiveKey = endpoint.getApiKey() != null ? endpoint.getApiKey() : "";
            ChatLanguageModel chatModel = OpenAiChatModel.builder()
                    .baseUrl(endpoint.getBaseUrl())
                    .modelName(endpoint.getModelName() != null ? endpoint.getModelName() : "default")
                    .apiKey(effectiveKey)
                    .temperature(0.7)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();

            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new SystemMessage(systemPrompt));
            }
            messages.add(new UserMessage(userPrompt));

            ChatResponse response = chatModel.chat(messages);
            return response.aiMessage().text();
        } catch (Exception e) {
            log.error("Custom LLM LangChain4j chat error", e);
            // Fall back to raw HTTP on failure
            log.info("Falling back to raw HTTP for custom LLM chat");
            return sendRequest(endpoint, systemPrompt, userPrompt);
        }
    }

    private String streamingChatWithLangChain4j(CustomLlmEndpoint endpoint, String systemPrompt,
                                                 String userPrompt, Consumer<String> tokenConsumer) {
        try {
            String effectiveKey = endpoint.getApiKey() != null ? endpoint.getApiKey() : "";
            StreamingChatLanguageModel streamingModel = OpenAiStreamingChatModel.builder()
                    .baseUrl(endpoint.getBaseUrl())
                    .modelName(endpoint.getModelName() != null ? endpoint.getModelName() : "default")
                    .apiKey(effectiveKey)
                    .temperature(0.7)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .build();

            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(new SystemMessage(systemPrompt));
            }
            messages.add(new UserMessage(userPrompt));

            StringBuilder fullResponse = new StringBuilder();
            streamingModel.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    fullResponse.append(token);
                    tokenConsumer.accept(token);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    log.info("Custom LLM streaming complete");
                }

                @Override
                public void onError(Throwable error) {
                    log.error("Custom LLM streaming error: {}", error.getMessage());
                }
            });
            return fullResponse.toString();
        } catch (Exception e) {
            log.error("Custom LLM LangChain4j streaming error", e);
            log.info("Falling back to raw HTTP for custom LLM streaming");
            return sendStreamingRequest(endpoint, systemPrompt, userPrompt, tokenConsumer);
        }
    }

    // --- Raw HTTP fallback for api-key auth and failures ---

    @SuppressWarnings("unchecked")
    private String sendRequest(CustomLlmEndpoint endpoint, String systemPrompt, String userPrompt) {
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
                var node = objectMapper.readTree(response.body());
                String content = node.at("/choices/0/message/content").asText();
                if (content == null || content.isBlank()) {
                    content = node.at("/choices/0/message/reasoning_content").asText();
                }
                return content != null ? content : "";
            } else {
                log.error("Custom LLM request failed: {} - {}", response.statusCode(), response.body());
                return "Error: HTTP " + response.statusCode();
            }
        } catch (Exception e) {
            log.error("Custom LLM request failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private String sendStreamingRequest(CustomLlmEndpoint endpoint, String systemPrompt, String userPrompt,
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
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                InputStream in = response.body();

                Thread reader = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6);
                                if (!data.equals("[DONE]")) {
                                    try {
                                        var node = objectMapper.readTree(data);
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
                reader.join(120000);
                return fullResponse.toString();
            } else {
                String error = "Error: HTTP " + response.statusCode();
                tokenConsumer.accept(error);
                return error;
            }
        } catch (Exception e) {
            log.error("Custom LLM streaming request failed", e);
            String error = "Error: " + e.getMessage();
            tokenConsumer.accept(error);
            return error;
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
}
