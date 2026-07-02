package com.agent.orchestrator.llm;

import com.agent.orchestrator.model.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapts our LlmProvider to LangChain4j ChatLanguageModel interface.
 * Preserves custom fields (reasoning_content, finish_reason) via LlmResponse.
 * Converts ToolSpecification to our internal tool config format.
 */
public class LangChain4jAdapter implements ChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jAdapter.class);

    private final LlmProvider provider;
    private final String modelName;

    public LangChain4jAdapter(LlmProvider provider, String modelName) {
        this.provider = provider;
        this.modelName = modelName;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String systemPrompt = extractSystemPrompt(request.messages());
        String userPrompt = extractUserPrompt(request.messages());
        Map<String, Object> config = buildConfig(request);

        LlmResponse response = provider.chat(modelName, systemPrompt, userPrompt, config);

        return toChatResponse(response, request);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return ChatRequestParameters.builder().build();
    }

    // ─── Helpers ───

    private Map<String, Object> buildConfig(ChatRequest request) {
        Map<String, Object> config = new HashMap<>();
        List<ToolSpecification> tools = request.toolSpecifications();
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> toolDefs = tools.stream()
                    .map(this::toolSpecToMap)
                    .collect(Collectors.toList());
            config.put("_tools", toolDefs);
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolSpecToMap(ToolSpecification spec) {
        Map<String, Object> function = new HashMap<>();
        // OpenAI requires ^[a-zA-Z0-9_-]+$ for function names
        String rawName = spec.name();
        String safeName = rawName.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase();
        function.put("name", safeName);
        function.put("description", spec.description() != null ? spec.description() : "");
        if (spec.parameters() != null) {
            function.put("parameters", jsonSchemaToMap(spec.parameters()));
        }
        // OpenAI-compatible tool format: {"type": "function", "function": {...}}
        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonSchemaToMap(JsonSchemaElement element) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (element instanceof JsonObjectSchema obj) {
            result.put("type", "object");
            if (obj.description() != null) result.put("description", obj.description());
            Map<String, Object> props = new LinkedHashMap<>();
            if (obj.properties() != null) {
                obj.properties().forEach((name, prop) -> props.put(name, jsonSchemaToMap(prop)));
            }
            if (!props.isEmpty()) result.put("properties", props);
            if (obj.required() != null && !obj.required().isEmpty()) {
                result.put("required", new ArrayList<>(obj.required()));
            }
            if (obj.additionalProperties() != null) {
                result.put("additionalProperties", obj.additionalProperties());
            }
        } else if (element instanceof JsonStringSchema str) {
            result.put("type", "string");
            if (str.description() != null) result.put("description", str.description());
        } else if (element instanceof JsonIntegerSchema num) {
            result.put("type", "integer");
            if (num.description() != null) result.put("description", num.description());
        } else if (element instanceof JsonBooleanSchema bool) {
            result.put("type", "boolean");
            if (bool.description() != null) result.put("description", bool.description());
        } else if (element instanceof JsonArraySchema arr) {
            result.put("type", "array");
            if (arr.description() != null) result.put("description", arr.description());
            if (arr.items() != null) result.put("items", jsonSchemaToMap(arr.items()));
        } else if (element instanceof JsonEnumSchema enm) {
            result.put("type", "string");
            if (enm.description() != null) result.put("description", enm.description());
            if (enm.enumValues() != null) result.put("enum", new ArrayList<>(enm.enumValues()));
        } else {
            result.put("type", "string");
        }
        return result;
    }

    private String extractSystemPrompt(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m.type() == ChatMessageType.SYSTEM)
                .map(m -> ((SystemMessage) m).text())
                .collect(Collectors.joining("\n"));
    }

    private String extractUserPrompt(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m.type() == ChatMessageType.USER) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(((UserMessage) m).singleText());
            } else if (m.type() == ChatMessageType.AI) {
                AiMessage ai = (AiMessage) m;
                if (ai.text() != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(ai.text());
                }
            } else if (m.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
                ToolExecutionResultMessage toolMsg = (ToolExecutionResultMessage) m;
                if (sb.length() > 0) sb.append("\n");
                sb.append("Tool result (").append(toolMsg.toolName()).append("): ")
                  .append(toolMsg.text());
            }
        }
        return sb.toString();
    }

    private ChatResponse toChatResponse(LlmResponse llmResp, ChatRequest request) {
        String text = llmResp.text() != null ? llmResp.text() : "";
        AiMessage aiMsg;
        String finishReason = llmResp.finishReason();

        if (text.contains("tool_calls") || text.contains("\"function\"")) {
            // Parse tool calls embedded in text
            aiMsg = AiMessage.from(null, parseToolCalls(text));
        } else {
            aiMsg = AiMessage.from(text);
        }

        return ChatResponse.builder()
                .aiMessage(aiMsg)
                .finishReason(parseFinishReason(finishReason))
                .build();
    }

    private FinishReason parseFinishReason(String raw) {
        if (raw == null) return FinishReason.STOP;
        return switch (raw.toLowerCase()) {
            case "stop" -> FinishReason.STOP;
            case "length" -> FinishReason.LENGTH;
            case "tool_calls" -> FinishReason.TOOL_EXECUTION;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.OTHER;
        };
    }

    private List<ToolExecutionRequest> parseToolCalls(String text) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            // Find JSON array in text
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String json = text.substring(start, end + 1);
                com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(json);
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode tc : arr) {
                        String id = tc.path("id").asText("");
                        String name = tc.path("function").path("name").asText("");
                        String args = tc.path("function").path("arguments").asText("{}");
                        if (!name.isEmpty()) {
                            requests.add(ToolExecutionRequest.builder()
                                    .id(id.isEmpty() ? UUID.randomUUID().toString() : id)
                                    .name(name)
                                    .arguments(args)
                                    .build());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse tool calls from text: {}", e.getMessage());
        }
        return requests;
    }
}
