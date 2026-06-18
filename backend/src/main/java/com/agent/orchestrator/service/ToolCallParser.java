package com.agent.orchestrator.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ToolCallParser {

    private static final Logger log = LoggerFactory.getLogger(ToolCallParser.class);

    private static final AtomicInteger callIdGen = new AtomicInteger(1);

    public List<Map<String, Object>> parse(String response) {
        List<Map<String, Object>> calls = new ArrayList<>();
        if (response == null || !response.contains("tool_calls")) {
            return calls;
        }

        try {
            String json = response;
            if (json.contains("```")) {
                json = json.replaceAll("```json\\s*", "");
                json = json.replaceAll("```\\s*", "");
                json = json.trim();
            }

            int toolCallsIdx = json.indexOf("\"tool_calls\"");
            if (toolCallsIdx < 0) {
                toolCallsIdx = json.indexOf("tool_calls");
            }
            if (toolCallsIdx < 0) return calls;

            int arrayStart = json.indexOf("[", toolCallsIdx);
            if (arrayStart < 0) return calls;

            int arrayEnd = findMatchingBracket(json, arrayStart);
            if (arrayEnd < 0) return calls;

            String toolsJson = json.substring(arrayStart, arrayEnd + 1);

            ObjectMapper mapper = new ObjectMapper();
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = mapper.readValue(toolsJson, List.class);
                calls.addAll(parsed);
                return calls;
            } catch (Exception e) {
                log.debug("Strict JSON parse failed: {}", e.getMessage());
            }

            try {
                ObjectMapper lenientMapper = new ObjectMapper()
                        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                        .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
                        .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = lenientMapper.readValue(toolsJson, List.class);
                calls.addAll(parsed);
                return calls;
            } catch (Exception e) {
                log.debug("Lenient JSON parse failed: {}", e.getMessage());
            }

            ObjectMapper lenientMapper = new ObjectMapper()
                    .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                    .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true)
                    .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);

            List<String> toolCallObjects = extractTopLevelObjects(toolsJson);
            for (String obj : toolCallObjects) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = lenientMapper.readValue(obj, Map.class);
                    calls.add(parsed);
                } catch (Exception e) {
                    log.debug("Failed to parse individual tool call, trying regex fallback: {}", e.getMessage());
                    Map<String, Object> fallbackCall = extractToolCallWithRegex(obj);
                    if (fallbackCall != null && fallbackCall.get("name") != null) {
                        fallbackCall.putIfAbsent("id", "call_" + calls.size());
                        calls.add(fallbackCall);
                    }
                }
            }

            if (calls.isEmpty()) {
                String diag = toolsJson.length() > 200 ? toolsJson.substring(0, 200) + "..." : toolsJson;
                log.warn("All tool call parsing fallbacks exhausted. Response snippet: {}", diag);
            }

        } catch (Exception e) {
            log.warn("Failed to parse tool calls: {}", e.getMessage(), e);
        }

        // Ensure every tool call has a non-null id — guards against NPE downstream
        for (Map<String, Object> call : calls) {
            if (call.get("id") == null || ((String) call.get("id")).isBlank()) {
                call.put("id", "call_" + callIdGen.getAndIncrement());
            }
        }
        return calls;
    }

    public static int findMatchingBracket(String json, int startIdx) {
        char openBracket = json.charAt(startIdx);
        char closeBracket = (openBracket == '[') ? ']' : '}';
        int depth = 0;
        boolean inString = false;

        for (int i = startIdx; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == openBracket) {
                depth++;
            } else if (c == closeBracket) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private List<String> extractTopLevelObjects(String jsonArray) {
        List<String> objects = new ArrayList<>();
        int i = 0;
        while (i < jsonArray.length()) {
            char c = jsonArray.charAt(i);
            if (c == '{') {
                int end = findMatchingBracket(jsonArray, i);
                if (end > i) {
                    objects.add(jsonArray.substring(i, end + 1));
                    i = end + 1;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        return objects;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractToolCallWithRegex(String text) {
        Map<String, Object> call = new HashMap<>();

        Pattern namePat = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher nameMatcher = namePat.matcher(text);
        if (nameMatcher.find()) {
            call.put("name", nameMatcher.group(1));
        }

        int argStart = text.indexOf("\"arguments\"");
        if (argStart < 0) {
            argStart = text.indexOf("arguments");
        }
        if (argStart > 0) {
            int objStart = text.indexOf("{", argStart);
            if (objStart > 0) {
                int objEnd = findMatchingBracket(text, objStart);
                if (objEnd > objStart) {
                    String argsJson = text.substring(objStart, objEnd + 1);
                    try {
                        ObjectMapper lenientMapper = new ObjectMapper()
                                .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                                .configure(JsonParser.Feature.ALLOW_UNQUOTED_CONTROL_CHARS, true);
                        Map<String, Object> args = lenientMapper.readValue(argsJson, Map.class);
                        call.put("arguments", args);
                    } catch (Exception e) {
                        Map<String, Object> fallbackArgs = extractArgumentsWithRegex(argsJson);
                        call.put("arguments", fallbackArgs);
                    }
                }
            }
        }

        return call.isEmpty() ? null : call;
    }

    private Map<String, Object> extractArgumentsWithRegex(String argsJson) {
        Map<String, Object> args = new HashMap<>();

        Pattern pathPat = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
        Matcher pathMatcher = pathPat.matcher(argsJson);
        if (pathMatcher.find()) {
            args.put("path", pathMatcher.group(1));
        }

        int contentKeyStart = argsJson.indexOf("\"content\"");
        if (contentKeyStart < 0) {
            contentKeyStart = argsJson.indexOf("content");
        }
        if (contentKeyStart > 0) {
            int colonIdx = argsJson.indexOf(":", contentKeyStart);
            int quoteStart = argsJson.indexOf("\"", colonIdx);
            if (quoteStart > colonIdx) {
                int endMarker = argsJson.lastIndexOf("\"");
                if (endMarker > quoteStart) {
                    String content = argsJson.substring(quoteStart + 1, endMarker);
                    content = content.replace("\\n", "\n").replace("\\t", "\t")
                            .replace("\\\"", "\"").replace("\\\\", "\\");
                    args.put("content", content);
                }
            }
        }

        return args;
    }
}
