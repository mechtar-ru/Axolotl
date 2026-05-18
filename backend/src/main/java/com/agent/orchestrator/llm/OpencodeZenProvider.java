package com.agent.orchestrator.llm;

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
public class OpencodeZenProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpencodeZenProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${axolotl.llm.zen.base-url:https://opencode.ai/zen/v1}")
    private String baseUrl;

    @Value("${axolotl.llm.zen.api-key:}")
    private String apiKey;

    @Value("${axolotl.llm.zen.default-model:big-pickle}")
    private String defaultModel;

    @Value("${axolotl.llm.zen.timeout:3600}")
    private int timeoutSeconds;

    public OpencodeZenProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        String effectiveModel = resolveModel(model);
        
        if (apiKey == null || apiKey.isBlank()) {
            return "OpenCode Zen: API ключ не настроен. Установите ZEN_API_KEY в .env файле";
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

            log.info("OpenCode Zen запрос: model={}", effectiveModel);

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.error("OpenCode Zen первая попытка неудачна: тип={} msg={}", e.getClass().getName(), e.getMessage(), e);
                // Retry once with a fresh HttpClient to rule out shared-state issues
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                HttpClient retryClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest retryRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .build();
                log.info("OpenCode Zen повторная попытка: model={}", effectiveModel);
                response = retryClient.send(retryRequest, HttpResponse.BodyHandlers.ofString());
            }

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode message = root.path("choices").path(0).path("message");
                String content = message.path("content").asText("");
                int tokens = root.path("usage").path("total_tokens").asInt(0);

                // Check for structured tool_calls from native OpenAI-compatible API
                JsonNode toolCalls = message.path("tool_calls");
                if (toolCalls.isArray() && toolCalls.size() > 0) {
                    StringBuilder sb = new StringBuilder();
                    if (content != null && !content.isBlank()) {
                        sb.append(content).append("\n");
                    }
                    // Convert API tool_calls to text format parseToolCalls() expects
                    var convertedCalls = objectMapper.createArrayNode();
                    for (int i = 0; i < toolCalls.size(); i++) {
                        JsonNode tc = toolCalls.get(i);
                        var call = objectMapper.createObjectNode();
                        call.put("id", tc.path("id").asText("call_" + i));
                        call.put("name", tc.path("function").path("name").asText(""));
                        String argsStr = tc.path("function").path("arguments").asText("{}");
                        try {
                            call.set("arguments", objectMapper.readTree(argsStr));
                        } catch (Exception e) {
                            call.set("arguments", objectMapper.createObjectNode());
                        }
                        convertedCalls.add(call);
                    }
                    var wrapper = objectMapper.createObjectNode();
                    wrapper.set("tool_calls", convertedCalls);
                    String toolCallsJson = objectMapper.writeValueAsString(wrapper);
                    // Parse to compact string to strip any pretty-printing
                    sb.append(toolCallsJson);
                    log.info("OpenCode Zen ответ ({} токенов, {} tool calls)", tokens, toolCalls.size());
                    return sb.toString();
                }

                log.info("OpenCode Zen ответ ({} токенов): {}", tokens,
                        content.length() > 100 ? content.substring(0, 100) + "..." : content);
                return content;
            } else {
                String error = "OpenCode Zen ошибка (HTTP " + response.statusCode() + "): " + response.body();
                log.error(error);
                return error;
            }
        } catch (Exception e) {
            String error = "OpenCode Zen недоступен: " + e.getMessage();
            log.error(error);
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
        return "zen";
    }

    @Override
    public List<String> listModels() {
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
                if (data.isArray() && data.size() > 0) {
                    List<String> models = new ArrayList<>();
                    for (JsonNode node : data) {
                        String id = node.path("id").asText();
                        if (id != null && !id.isBlank()) {
                            models.add(id);
                        }
                    }
                    return models;
                }
            }
        } catch (Exception e) {
            log.warn("Zen models API unavailable: {}", e.getMessage());
        }
        return List.of();
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
        return "zen".equalsIgnoreCase(model) || "opencode".equalsIgnoreCase(model);
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
            String error = "OpenCode Zen: API ключ не настроен";
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

            log.info("OpenCode Zen streaming запрос: model={}", effectiveModel);

            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.error("OpenCode Zen streaming первая попытка неудачна: тип={} msg={}", e.getClass().getName(), e.getMessage(), e);
                // Retry once with a fresh HttpClient
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                HttpClient retryClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest retryRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .build();
                log.info("OpenCode Zen streaming повторная попытка: model={}", effectiveModel);
                response = retryClient.send(retryRequest, HttpResponse.BodyHandlers.ofString());
            }
            StringBuilder fullResponse = new StringBuilder();

            // Track streaming tool calls: index → {id, name, arguments-builder}
            var streamingToolCalls = new java.util.concurrent.ConcurrentHashMap<Integer, java.util.Map<String, Object>>();

            BufferedReader reader = new BufferedReader(new StringReader(response.body()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith("data: ")) continue;
                String data = line.substring(6);
                if ("[DONE]".equals(data)) break;

                try {
                    JsonNode node = objectMapper.readTree(data);
                    JsonNode delta = node.path("choices").path(0).path("delta");

                    // Accumulate text content tokens
                    String token = delta.path("content").asText("");
                    if (!token.isEmpty()) {
                        fullResponse.append(token);
                        onToken.accept(token);
                    }

                    // Accumulate streaming tool call deltas
                    JsonNode deltaToolCalls = delta.path("tool_calls");
                    if (deltaToolCalls.isArray()) {
                        for (JsonNode tcChunk : deltaToolCalls) {
                            int idx = tcChunk.path("index").asInt(-1);
                            if (idx < 0) continue;

                            streamingToolCalls.putIfAbsent(idx, new java.util.HashMap<>());
                            var tc = streamingToolCalls.get(idx);

                            // First chunk: id + name
                            String chunkId = tcChunk.path("id").asText("");
                            if (!chunkId.isEmpty()) tc.put("id", chunkId);
                            String chunkName = tcChunk.path("function").path("name").asText("");
                            if (!chunkName.isEmpty()) tc.put("name", chunkName);

                            // Accumulate arguments incrementally
                            String argsDelta = tcChunk.path("function").path("arguments").asText("");
                            if (!argsDelta.isEmpty()) {
                                tc.computeIfAbsent("args_buf", k -> new StringBuilder());
                                ((StringBuilder) tc.get("args_buf")).append(argsDelta);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // After stream ends, append any accumulated tool calls to the response text
            if (!streamingToolCalls.isEmpty()) {
                var convertedCalls = objectMapper.createArrayNode();
                for (int idx : new java.util.TreeSet<>(streamingToolCalls.keySet())) {
                    var tc = streamingToolCalls.get(idx);
                    var call = objectMapper.createObjectNode();
                    if (tc.get("id") != null) call.put("id", (String) tc.get("id"));
                    else call.put("id", "call_" + idx);
                    if (tc.get("name") != null) call.put("name", (String) tc.get("name"));
                    else call.put("name", "");
                    if (tc.get("args_buf") != null) {
                        String argsStr = tc.get("args_buf").toString();
                        try {
                            call.set("arguments", objectMapper.readTree(argsStr));
                        } catch (Exception e) {
                            call.set("arguments", objectMapper.createObjectNode());
                        }
                    } else {
                        call.set("arguments", objectMapper.createObjectNode());
                    }
                    convertedCalls.add(call);
                }
                var wrapper = objectMapper.createObjectNode();
                wrapper.set("tool_calls", convertedCalls);
                String toolCallsJson = objectMapper.writeValueAsString(wrapper);
                fullResponse.append("\n").append(toolCallsJson);
            }

            return fullResponse.toString();
        } catch (Exception e) {
            String error = "OpenCode Zen streaming недоступен: " + e.getMessage();
            log.error(error);
            onToken.accept(error);
            return error;
        }
    }
}