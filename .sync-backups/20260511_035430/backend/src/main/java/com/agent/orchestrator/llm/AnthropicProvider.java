package com.agent.orchestrator.llm;

import com.agent.orchestrator.service.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Value("${axolotl.llm.anthropic.base-url:https://api.anthropic.com}")
    private String baseUrl;

    @Value("${axolotl.llm.anthropic.api-key:}")
    private String apiKey;

    @Value("${axolotl.llm.anthropic.default-model:claude-sonnet-4-20250514}")
    private String defaultModel;

    @Value("${axolotl.llm.anthropic.timeout:120}")
    private int timeoutSeconds;

    public AnthropicProvider(SettingsService settingsService) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.settingsService = settingsService;
    }

    private String getEffectiveApiKey() {
        String key = settingsService.getApiKey("anthropic");
        if (key != null && !key.isBlank()) return key;
        return apiKey;
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        String effectiveModel = resolveModel(model);
        String effectiveKey = getEffectiveApiKey();
        if (effectiveKey == null || effectiveKey.isBlank()) {
            return "Anthropic: API ключ не настроен. Установите axolotl.llm.anthropic.api-key в application.properties";
        }

        try {
            var requestBody = new java.util.HashMap<String, Object>();
            requestBody.put("model", effectiveModel);
            requestBody.put("max_tokens", 4096);

            var messages = new ArrayList<Map<String, String>>();
            messages.add(Map.of("role", "user", "content", userPrompt));
            requestBody.put("messages", messages);

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                requestBody.put("system", systemPrompt);
            }

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", getEffectiveApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            log.info("Anthropic запрос: model={}", effectiveModel);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode content = root.path("content");
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText(""));
                    }
                }
                int inputTokens = root.path("usage").path("input_tokens").asInt(0);
                int outputTokens = root.path("usage").path("output_tokens").asInt(0);
                log.info("Anthropic ответ ({}+{} токенов): {}...", inputTokens, outputTokens,
                        sb.substring(0, Math.min(100, sb.length())));
                return sb.toString();
            } else {
                String error = "Anthropic ошибка (HTTP " + response.statusCode() + "): " + response.body();
                log.error(error);
                return error;
            }
        } catch (Exception e) {
            String error = "Anthropic недоступен: " + e.getMessage();
            log.error(error);
            return error;
        }
    }

    @Override
    public boolean isAvailable() {
        return getEffectiveApiKey() != null && !getEffectiveApiKey().isBlank();
    }

    @Override
    public String getName() {
        return "anthropic";
    }

    @Override
    public List<String> listModels() {
        return List.of("claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-haiku-4-20250414");
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    private String resolveModel(String model) {
        if (model == null || model.isBlank() || isProviderName(model)) return defaultModel;
        return model;
    }

    private boolean isProviderName(String model) {
        return "anthropic".equalsIgnoreCase(model) || "claude".equalsIgnoreCase(model);
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String streamingChat(String model, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> onToken) {
        String effectiveModel = resolveModel(model);
        if (getEffectiveApiKey() == null || getEffectiveApiKey().isBlank()) {
            String error = "Anthropic: API ключ не настроен";
            onToken.accept(error);
            return error;
        }

        try {
            var requestBody = new java.util.HashMap<String, Object>();
            requestBody.put("model", effectiveModel);
            requestBody.put("max_tokens", 4096);
            requestBody.put("stream", true);

            var messages = new ArrayList<Map<String, String>>();
            messages.add(Map.of("role", "user", "content", userPrompt));
            requestBody.put("messages", messages);

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                requestBody.put("system", systemPrompt);
            }

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", getEffectiveApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            log.info("Anthropic streaming запрос: model={}", effectiveModel);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            StringBuilder fullResponse = new StringBuilder();

            // Anthropic streams Server-Sent Events (SSE)
            BufferedReader reader = new BufferedReader(new StringReader(response.body()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data: ")) continue;
                String data = line.substring(6);

                try {
                    JsonNode event = objectMapper.readTree(data);
                    String type = event.path("type").asText("");
                    if ("content_block_delta".equals(type)) {
                        String token = event.path("delta").path("text").asText("");
                        if (!token.isEmpty()) {
                            fullResponse.append(token);
                            onToken.accept(token);
                        }
                    }
                    if ("message_stop".equals(type)) break;
                } catch (Exception ignored) {}
            }

            return fullResponse.toString();
        } catch (Exception e) {
            String error = "Anthropic streaming недоступен: " + e.getMessage();
            log.error(error);
            onToken.accept(error);
            return error;
        }
    }
}
