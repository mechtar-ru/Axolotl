package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Node.TransformStep;
import com.agent.orchestrator.model.Node.TransformRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TransformService {
    private static final Logger log = LoggerFactory.getLogger(TransformService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public String applyTransforms(String input, List<TransformStep> transforms) {
        if (input == null) return null;
        if (transforms == null || transforms.isEmpty()) return input;

        String result = input;
        for (TransformStep step : transforms) {
            result = applyStep(result, step);
            if (result == null) return null;
        }
        return result;
    }

    private String applyStep(String input, TransformStep step) {
        if (step == null || step.getType() == null) return input;
        Map<String, Object> config = step.getConfig();

        return switch (step.getType()) {
            case "jsonField" -> extractJsonField(input, config);
            case "jsonPath" -> extractJsonPath(input, config);
            case "regex" -> extractRegex(input, config);
            case "delimited" -> extractDelimited(input, config);
            case "replace" -> replace(input, config);
            case "prepend" -> prepend(input, config);
            case "append" -> append(input, config);
            case "lower" -> input.toLowerCase();
            case "upper" -> input.toUpperCase();
            case "trim" -> input.trim();
            case "template" -> applyTemplate(input, config);
            case "jsonParse" -> parseJson(input, config);
            case "jsonStringify" -> stringifyJson(input, config);
            case "ifEmpty" -> ifEmpty(input, config);
            default -> input;
        };
    }

    private String extractJsonField(String input, Map<String, Object> config) {
        String field = (String) config.get("field");
        if (field == null || field.isEmpty()) return input;
        try {
            // Strip markdown code blocks before parsing JSON
            String jsonInput = stripMarkdownCodeBlocks(input);
            JsonNode node = mapper.readTree(jsonInput);
            JsonNode value = node.get(field);
            return value != null ? value.asText() : null;
        } catch (Exception e) {
            log.warn("JSON field extraction failed for field {}: {}", field, e.getMessage());
            return null;
        }
    }

    private String stripMarkdownCodeBlocks(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        
        // Try to find JSON code block markers
        Pattern blockPattern = Pattern.compile("```json\\s*([\\s\\S]*?)```", Pattern.MULTILINE);
        Matcher blockMatcher = blockPattern.matcher(trimmed);
        if (blockMatcher.find()) {
            String jsonContent = blockMatcher.group(1).trim();
            if (jsonContent.startsWith("{")) return jsonContent;
        }
        
        // Try ``` without language specifier
        Pattern plainBlock = Pattern.compile("```\\s*([\\s\\S]*?)```", Pattern.MULTILINE);
        Matcher plainMatcher = plainBlock.matcher(trimmed);
        if (plainMatcher.find()) {
            String jsonContent = plainMatcher.group(1).trim();
            if (jsonContent.startsWith("{")) return jsonContent;
        }
        
        // Fallback: find first { and last } that form valid JSON
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            String potential = trimmed.substring(firstBrace, lastBrace + 1);
            // Quick validity check - must have at least one key
            if (potential.contains(":")) {
                return potential;
            }
        }
        
        return input;
    }

    private String extractJsonPath(String input, Map<String, Object> config) {
        String path = (String) config.get("path");
        if (path == null || path.isEmpty()) return input;
        try {
            // Strip markdown code blocks before parsing JSON
            String jsonInput = stripMarkdownCodeBlocks(input);
            JsonNode node = mapper.readTree(jsonInput);
            JsonNode result = node.at(path);
            return result.isMissingNode() ? null : result.toString();
        } catch (Exception e) {
            log.warn("JSONPath extraction failed for path {}: {}", path, e.getMessage());
            return null;
        }
    }

    private String extractRegex(String input, Map<String, Object> config) {
        String pattern = (String) config.get("pattern");
        if (pattern == null || pattern.isEmpty()) return input;
        try {
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher(input);
            if (m.find()) {
                return m.group(1) != null ? m.group(1) : m.group(0);
            }
            return null;
        } catch (Exception e) {
            log.warn("Regex extraction failed for pattern {}: {}", pattern, e.getMessage());
            return null;
        }
    }

    private String extractDelimited(String input, Map<String, Object> config) {
        String delimiter = (String) config.get("delimiter");
        Integer index = config.get("index") != null ? (Integer) config.get("index") : 0;
        if (delimiter == null || delimiter.isEmpty()) return input;
        String[] parts = input.split(Pattern.quote(delimiter));
        if (index >= 0 && index < parts.length) {
            return parts[index].trim();
        }
        return null;
    }

    private String replace(String input, Map<String, Object> config) {
        String find = (String) config.get("find");
        String with = (String) config.get("replace");
        if (find == null) return input;
        return input.replace(find, with != null ? with : "");
    }

    private String prepend(String input, Map<String, Object> config) {
        String text = (String) config.get("text");
        return text != null ? text + input : input;
    }

    private String append(String input, Map<String, Object> config) {
        String text = (String) config.get("text");
        return text != null ? input + text : input;
    }

    private String applyTemplate(String input, Map<String, Object> config) {
        String template = (String) config.get("template");
        if (template == null) return input;
        return template.replace("{{value}}", input);
    }

    private String parseJson(String input, Map<String, Object> config) {
        try {
            JsonNode node = mapper.readTree(input);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String stringifyJson(String input, Map<String, Object> config) {
        try {
            JsonNode node = mapper.readTree(input);
            return node.toString();
        } catch (Exception e) {
            return input;
        }
    }

    private String ifEmpty(String input, Map<String, Object> config) {
        if (input == null || input.isEmpty()) {
            String fallback = (String) config.get("fallback");
            return fallback != null ? fallback : "";
        }
        return input;
    }

    public String evaluateRoute(String transformedValue, TransformRoute route) {
        if (route == null || route.getCondition() == null) {
            return transformedValue;
        }
        String condition = route.getCondition().toLowerCase();

        return switch (condition) {
            case "not empty", "isnotempty" -> (transformedValue != null && !transformedValue.isEmpty()) ? transformedValue : null;
            case "empty", "isempty" -> (transformedValue == null || transformedValue.isEmpty()) ? transformedValue : null;
            case "iserror" -> isErrorOutput(transformedValue) ? transformedValue : null;
            default -> transformedValue;
        };
    }

    private boolean isErrorOutput(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase();
        return lower.contains("error") || lower.contains("exception") || lower.contains("failed");
    }
}