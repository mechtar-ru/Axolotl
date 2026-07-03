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

    // OpenRouter identification headers
    private static final String OPENROUTER_REFERER = "https://axolotl.app";
    private static final String OPENROUTER_TITLE = "Axolotl";

    private OpenAiChatClient() {
    }

    /**
     * If the baseUrl contains "openrouter.ai", add the required HTTP-Referer and X-Title headers.
     */
    static void addOpenRouterHeadersIfNeeded(HttpRequest.Builder builder, String baseUrl) {
        if (baseUrl != null && baseUrl.contains("openrouter.ai")) {
            builder.header("HTTP-Referer", OPENROUTER_REFERER);
            builder.header("X-Title", OPENROUTER_TITLE);
        }
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
        return chat(apiKey, baseUrl, model, systemPrompt, userPrompt, usage, timeoutSec, httpVersion, null);
    }

    public static LlmResponse chat(String apiKey, String baseUrl, String model,
                                   String systemPrompt, String userPrompt,
                                   LlmUsage usage, int timeoutSec,
                                   HttpClient.Version httpVersion,
                                   List<Map<String, Object>> tools) throws Exception {
        String jsonBody = buildRequestBody(model, systemPrompt, userPrompt, false, tools);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(httpVersion)
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSec));
        addOpenRouterHeadersIfNeeded(builder, baseUrl);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

        int maxRetries = 2;
        int retryDelay = 1000;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return parseResponse(response.body(), usage);
                }
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    // retry on rate limit or server error
                    if (attempt < maxRetries) {
                        Thread.sleep(retryDelay * (attempt + 1));
                        continue;
                    }
                    throw new RuntimeException("HTTP " + response.statusCode() + ": " +
                            truncate(response.body(), 500));
                }
                throw new RuntimeException("HTTP " + response.statusCode() + ": " +
                        truncate(response.body(), 500));
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    Thread.sleep(retryDelay * (attempt + 1));
                }
            }
        }
        if (lastException != null) throw new RuntimeException(lastException);
        throw new RuntimeException("OpenAI API request failed after " + (maxRetries + 1) + " attempts");
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
        return streamingChat(apiKey, baseUrl, model, systemPrompt, userPrompt, onToken, timeoutSec, httpVersion, null);
    }

    public static LlmResponse streamingChat(String apiKey, String baseUrl, String model,
                                             String systemPrompt, String userPrompt,
                                             Consumer<String> onToken,
                                             int timeoutSec,
                                             HttpClient.Version httpVersion,
                                             List<Map<String, Object>> tools) throws Exception {
        String jsonBody = buildRequestBody(model, systemPrompt, userPrompt, true, tools);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(httpVersion)
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSec));
        addOpenRouterHeadersIfNeeded(builder, baseUrl);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

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
        String[] finishReasonHolder = {null};
        boolean lastLineWasDone = false;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if (data.equals("[DONE]")) {
                    lastLineWasDone = true;
                    break;
                }
                if (data.isEmpty()) continue;

                try {
                    JsonNode node = MAPPER.readTree(data);

                    // Capture finish_reason from the chunk (present on last non-DONE chunk)
                    JsonNode frNode = node.at("/choices/0/finish_reason");
                    if (!frNode.isMissingNode() && !frNode.isNull()) {
                        finishReasonHolder[0] = frNode.asText();
                    }

                    String reasoning = node.at("/choices/0/delta/reasoning_content").asText("");
                    if (reasoning.isEmpty()) {
                        reasoning = node.at("/choices/0/delta/reasoning").asText("");
                    }
                    if (!reasoning.isEmpty()) {
                        reasoningBuilder.append(reasoning);
                    }

                    String content = node.at("/choices/0/delta/content").asText("");
                    if (content.isEmpty()) {
                        content = node.at("/choices/0/delta/reasoning").asText("");
                    }
                    if (!content.isEmpty()) {
                        contentBuilder.append(content);
                        if (onToken != null) {
                            onToken.accept(content);
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed SSE events
                    log.warn("SSE parse warning: {}", e.getMessage(), e);
                }
            }
        }

        if (contentBuilder.length() > 0 && !lastLineWasDone) {
            log.warn("SSE stream ended without [DONE] marker — response may be truncated");
        }

        String text = contentBuilder.toString();
        String reasoning = reasoningBuilder.length() > 0 ? reasoningBuilder.toString() : null;
        String finishReason = finishReasonHolder[0];

        if (reasoning != null && !reasoning.isBlank()) {
            return LlmResponse.full(text, reasoning, finishReason);
        }
        return finishReason != null ? LlmResponse.full(text, null, finishReason) : LlmResponse.textOnly(text);
    }

    /**
     * Convert a Tool definition to OpenAI tools JSON format.
     * Input: Map with "name", "description", "input_schema" keys.
     * Output: Map in OpenAI tools format.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toolToOpenAiFormat(Map<String, Object> toolDef) {
        String name = (String) toolDef.get("name");
        String description = (String) toolDef.get("description");
        Object inputSchema = toolDef.get("input_schema");

        Map<String, Object> function = new HashMap<>();
        function.put("name", name != null ? name : "unknown");
        function.put("description", description != null ? description : "");

        if (inputSchema instanceof String) {
            try {
                function.put("parameters", MAPPER.readTree((String) inputSchema));
            } catch (Exception e) {
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("type", "object");
                fallback.put("properties", new HashMap<>());
                function.put("parameters", fallback);
            }
        } else if (inputSchema instanceof Map) {
            function.put("parameters", inputSchema);
        } else {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("type", "object");
            fallback.put("properties", new HashMap<>());
            function.put("parameters", fallback);
        }

        return Map.of("type", "function", "function", function);
    }

    // ──────────────────────────────────────────────
    // Shared helpers
    // ──────────────────────────────────────────────

    /** Build the JSON request body. */
    private static String buildRequestBody(String model, String systemPrompt,
                                            String userPrompt, boolean stream) throws Exception {
        return buildRequestBody(model, systemPrompt, userPrompt, stream, null);
    }

    /** Build the JSON request body with optional structured tools. */
    static String buildRequestBody(String model, String systemPrompt,
                                   String userPrompt, boolean stream,
                                   List<Map<String, Object>> tools) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", 16384);

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        body.put("messages", messages);
        body.put("stream", stream);

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

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

        String finishReason = choice.path("finish_reason").asText(null);

        JsonNode message = choice.path("message");
        // content may be null when tool_calls are present — treat as empty text
        String text = message.has("content") && !message.path("content").isNull()
                ? message.path("content").asText()
                : "";
        String reasoning = message.path("reasoning_content").asText(null);
        if (reasoning == null) {
            reasoning = message.path("reasoning").asText(null);
        }
        // If content is null but reasoning exists, use reasoning as content
        if ((text == null || text.isBlank()) && reasoning != null && !reasoning.isBlank()) {
            text = reasoning;
        }

        // Extract tool_calls if present (OpenAI-compatible structured format)
        JsonNode toolCallsNode = message.path("tool_calls");
        if (!toolCallsNode.isMissingNode() && toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            // Serialize tool_calls to JSON text so ToolCallParser can process them
            try {
                String toolCallsJson = MAPPER.writeValueAsString(toolCallsNode);
                text = (text != null && !text.isBlank() ? text + "\n" : "") + toolCallsJson;
                log.debug("Extracted {} tool_calls from response, appended to text", toolCallsNode.size());
            } catch (Exception e) {
                log.warn("Failed to serialize tool_calls: {}", e.getMessage(), e);
            }
        }

        int tokens = !usageNode.isMissingNode() ? usageNode.path("total_tokens").asInt(0) : 0;
        log.info("OpenAI response received: {} tokens", tokens);
        // Response content logged only at TRACE level for debugging
        if (log.isTraceEnabled()) {
            log.trace("OpenAI response: {}", truncate(text, 100));
        }

        if (reasoning != null && !reasoning.isBlank()) {
            log.info("  reasoning_content present ({} chars)", reasoning.length());
            return LlmResponse.full(text, reasoning, finishReason);
        }
        return finishReason != null ? LlmResponse.full(text, null, finishReason) : LlmResponse.textOnly(text);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // TODO(H43): Add unit tests for SSE parsing, streaming failure,
    // tool formatting, and retry logic
}
