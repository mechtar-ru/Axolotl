package com.agent.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class OpenAiProvider implements LlmProvider {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${axolotl.llm.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${axolotl.llm.openai.api-key:}")
    private String apiKey;

    @Value("${axolotl.llm.openai.default-model:gpt-4o-mini}")
    private String defaultModel;

    @Value("${axolotl.llm.openai.timeout:120}")
    private int timeoutSeconds;

    public OpenAiProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        String effectiveModel = resolveModel(model);
        if (apiKey == null || apiKey.isBlank()) {
            return "OpenAI: API ключ не настроен. Установите axolotl.llm.openai.api-key в application.properties";
        }

        try {
            var messages = new ArrayList<Map<String, String>>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            var requestBody = new java.util.HashMap<String, Object>();
            requestBody.put("model", effectiveModel);
            requestBody.put("messages", messages);
            requestBody.put("stream", false);

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            System.out.println("🤖 OpenAI запрос: model=" + effectiveModel);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                int tokens = root.path("usage").path("total_tokens").asInt(0);
                System.out.println("🤖 OpenAI ответ (" + tokens + " токенов): " +
                        content.substring(0, Math.min(100, content.length())) + "...");
                return content;
            } else {
                String error = "OpenAI ошибка (HTTP " + response.statusCode() + "): " + response.body();
                System.err.println("❌ " + error);
                return error;
            }
        } catch (Exception e) {
            String error = "OpenAI недоступен: " + e.getMessage();
            System.err.println("❌ " + error);
            return error;
        }
    }

    @Override
    public boolean isAvailable() {
        if (apiKey == null || apiKey.isBlank()) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public List<String> listModels() {
        if (apiKey == null || apiKey.isBlank()) return List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode data = root.path("data");
                List<String> models = new ArrayList<>();
                for (JsonNode m : data) {
                    String id = m.path("id").asText("");
                    if (id.startsWith("gpt-")) {
                        models.add(id);
                    }
                }
                return models;
            }
        } catch (Exception e) {
            System.err.println("❌ Ошибка получения моделей OpenAI: " + e.getMessage());
        }
        return List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo");
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
        return "openai".equalsIgnoreCase(model) || "gpt".equalsIgnoreCase(model);
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String streamingChat(String model, String systemPrompt, String userPrompt,
                                 Map<String, Object> config, Consumer<String> onToken) {
        String effectiveModel = resolveModel(model);
        if (apiKey == null || apiKey.isBlank()) {
            String error = "OpenAI: API ключ не настроен";
            onToken.accept(error);
            return error;
        }

        try {
            var messages = new ArrayList<Map<String, String>>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            var requestBody = new java.util.HashMap<String, Object>();
            requestBody.put("model", effectiveModel);
            requestBody.put("messages", messages);
            requestBody.put("stream", true);

            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            System.out.println("🤖 OpenAI streaming запрос: model=" + effectiveModel);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            StringBuilder fullResponse = new StringBuilder();

            // OpenAI streams Server-Sent Events (SSE)
            BufferedReader reader = new BufferedReader(new StringReader(response.body()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data: ")) continue;
                String data = line.substring(6);
                if ("[DONE]".equals(data)) break;

                try {
                    JsonNode node = objectMapper.readTree(data);
                    String token = node.path("choices").path(0).path("delta").path("content").asText("");
                    if (!token.isEmpty()) {
                        fullResponse.append(token);
                        onToken.accept(token);
                    }
                } catch (Exception ignored) {}
            }

            return fullResponse.toString();
        } catch (Exception e) {
            String error = "OpenAI streaming недоступен: " + e.getMessage();
            System.err.println("❌ " + error);
            onToken.accept(error);
            return error;
        }
    }
}
