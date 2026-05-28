package com.agent.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Makes raw HTTP calls to OpenAI-compatible /v1/chat/completions endpoints
 * and extracts both {@code content} and {@code reasoning_content} from the response.
 * <p>
 * Used by {@link OpenAiProvider}, {@link DeepSeekProvider}, {@link OpencodeZenProvider},
 * and {@link CustomLlmProvider} to capture reasoning/thought content that LangChain4j
 * does not expose through its standard response objects.
 */
public final class OpenAiChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiChatClient() {
    }

    // ──────────────────────────────────────────────
    // Non-streaming chat
    // ──────────────────────────────────────────────

    /**
     * Send a chat completion request via raw HTTP POST and return an {@link LlmResponse}
     * that includes both the main text and any {@code reasoning_content}.
     */
    public static LlmResponse chat(String apiKey, String baseUrl, String model,
                                   String systemPrompt, String userPrompt,
                                   LlmUsage usage, int timeoutSec) throws Exception {
        return chat(apiKey, baseUrl, model, systemPrompt, userPrompt, usage, timeoutSec,
                HttpClient.Version.HTTP_1_1);
    }

    /**
     * Send a chat completion request with explicit HTTP version.
     */
    public static LlmResponse chat(String apiKey, String baseUrl, String model,
                                   String systemPrompt, String userPrompt,
                                   LlmUsage usage, int timeoutSec,
                                   HttpClient.Version httpVersion) throws Exception {
        String jsonBody = buildRequestBody(model, systemPrompt, userPrompt, false);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(httpVersion)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " +
                    truncate(response.body(), 500));
        }

        return parseResponse(response.body(), usage);
    }

    // ──────────────────────────────────────────────
    // Streaming chat (SSE) with reasoning extraction
    // ──────────────────────────────────────────────

    /**
     * Send a streaming chat completion request via raw HTTP SSE and return an
     * {@link LlmResponse} that includes both the main text and any
     * {@code reasoning_content} collected during streaming.
     *
     * @param apiKey        Bearer token for authorization
     * @param baseUrl       Base URL of the OpenAI-compatible API
     * @param model         Model identifier
     * @param systemPrompt  Optional system message
     * @param userPrompt    User message content
     * @param onToken       Consumer for each content token (forwarded in real-time)
     * @param timeoutSec    HTTP request timeout in seconds
     * @return {@link LlmResponse} with text and optional reasoning content
     * @throws Exception if the HTTP request fails or the response cannot be parsed
     */
    public static LlmResponse streamingChat(String apiKey, String baseUrl, String model,
                                             String systemPrompt, String userPrompt,
                                             Consumer<String> onToken,
                                             int timeoutSec) throws Exception {
        return streamingChat(apiKey, baseUrl, model, systemPrompt, userPrompt, onToken,
                timeoutSec, HttpClient.Version.HTTP_1_1);
    }

    /**
     * Send a streaming chat completion request with explicit HTTP version.
     */
    public static LlmResponse streamingChat(String apiKey, String baseUrl, String model,
                                             String systemPrompt, String userPrompt,
                                             Consumer<String> onToken,
                                             int timeoutSec,
                                             HttpClient.Version httpVersion) throws Exception {
        String jsonBody = buildRequestBody(model, systemPrompt, userPrompt, true);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(httpVersion)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<java.io.InputStream> rawResponse = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (rawResponse.statusCode() != 200) {
            // Try to read error body for a better message
            String errorBody = rawResponse.body() != null ? new String(rawResponse.body().readAllBytes()) : "";
            throw new RuntimeException("HTTP " + rawResponse.statusCode() + ": " +
                    truncate(errorBody, 500));
        }

        return parseSseResponse(rawResponse.body(), onToken);
    }

    /**
     * Read the SSE event stream, extract reasoning_content and content,
     * forward content tokens to the consumer, and return LlmResponse.
     */
    static LlmResponse parseSseResponse(java.io.InputStream inputStream,
                                          Consumer<String> onToken) throws Exception {
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if (data.equals("[DONE]")) break;
                if (data.isEmpty()) continue;

                try {
                    JsonNode node = MAPPER.readTree(data);

                    String reasoning = node.at("/choices/0/delta/reasoning_content").asText("");
                    if (!reasoning.isEmpty()) {
                        reasoningBuilder.append(reasoning);
                    }

                    String content = node.at("/choices/0/delta/content").asText("");
                    if (!content.isEmpty()) {
                        contentBuilder.append(content);
                        if (onToken != null) {
                            onToken.accept(content);
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed SSE events
                    log.warn("SSE parse warning: {}", e.getMessage());
                }
            }
        }

        String text = contentBuilder.toString();
        String reasoning = reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null;

        if (reasoning != null && !reasoning.isBlank()) {
            return new LlmResponse(text, reasoning);
        }
        return LlmResponse.textOnly(text);
    }

    // ──────────────────────────────────────────────
    // Shared helpers
    // ──────────────────────────────────────────────

    /** Build the JSON request body. */
    private static String buildRequestBody(String model, String systemPrompt,
                                            String userPrompt, boolean stream) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        body.put("messages", messages);
        body.put("stream", stream);

        return MAPPER.writeValueAsString(body);
    }

    /** Parse the non-streaming JSON response body and extract text + reasoning content. */
    static LlmResponse parseResponse(String rawBody, LlmUsage usage) throws Exception {
        JsonNode root = MAPPER.readTree(rawBody);

        JsonNode usageNode = root.path("usage");
        if (usage != null && !usageNode.isMissingNode()) {
            usage.setInputTokens(usageNode.path("prompt_tokens").asInt(0));
            usage.setOutputTokens(usageNode.path("completion_tokens").asInt(0));
            usage.setTotalTokens(usageNode.path("total_tokens").asInt(0));
        }

        JsonNode choice = root.path("choices").get(0);
        if (choice == null || choice.isMissingNode()) {
            throw new RuntimeException("No choices in response: " + truncate(rawBody, 500));
        }

        JsonNode message = choice.path("message");
        String text = message.path("content").asText();
        String reasoning = message.path("reasoning_content").asText(null);

        int tokens = !usageNode.isMissingNode() ? usageNode.path("total_tokens").asInt(0) : 0;
        log.info("OpenAI response ({} tokens): {}", tokens, truncate(text, 100));

        if (reasoning != null && !reasoning.isBlank()) {
            log.info("  reasoning_content present ({} chars)", reasoning.length());
            return new LlmResponse(text, reasoning);
        }
        return LlmResponse.textOnly(text);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
