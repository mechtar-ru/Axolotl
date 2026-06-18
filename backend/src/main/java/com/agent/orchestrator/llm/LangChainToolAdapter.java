package com.agent.orchestrator.llm;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.service.ToolExecutor;
import com.agent.orchestrator.model.ToolPermission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bridges LangChain4j's {@link ToolSpecification} API with Axolotl's {@link ToolExecutor}.
 *
 * Converts Axolotl {@link Tool} definitions to LangChain4j {@link ToolSpecification} objects
 * and executes {@link ToolExecution} requests via Axolotl's {@link ToolExecutor}.
 */
public final class LangChainToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(LangChainToolAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LangChainToolAdapter() {
        // Utility class
    }

    /**
     * Convert a list of Axolotl {@link Tool} objects to LangChain4j
     * {@link ToolSpecification} objects.
     */
    public static List<ToolSpecification> toToolSpecifications(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) return List.of();

        return tools.stream()
                .map(tool -> {
                    var builder = ToolSpecification.builder()
                            .name(tool.getId())
                            .description(tool.getDescription());

                    // Enrich description with input schema if present
                    if (tool.getInputSchema() != null && !tool.getInputSchema().isBlank()) {
                        builder.description(tool.getDescription()
                                + "\n\nParameters: " + tool.getInputSchema());
                    }

                    return builder.build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Execute a LangChain4j {@link ToolExecutionRequest} using Axolotl's {@link ToolExecutor}.
     *
     * @param toolExecution A LangChain4j {@link ToolExecutionRequest} instance
     * @param toolExecutor  Axolotl's tool executor
     * @param permission    Tool permission constraints
     * @param schemaId      Current schema ID (for WebSocket events)
     * @param nodeId        Current node ID (for WebSocket events)
     * @return Axolotl {@link ToolResult}
     */
    public static ToolResult executeTool(ToolExecutionRequest toolExecution, ToolExecutor toolExecutor,
                                          ToolPermission permission, String schemaId, String nodeId) {
        try {
            String toolName = toolExecution.name();
            if (toolName == null) {
                return ToolResult.error("Cannot determine tool name from ToolExecutionRequest");
            }

            // arguments() returns a JSON string; parse into Map
            Map<String, Object> args;
            String argsJson = toolExecution.arguments();
            if (argsJson != null && !argsJson.isBlank()) {
                args = MAPPER.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
            } else {
                args = Map.of();
            }

            return toolExecutor.execute(toolName, args, permission, schemaId, nodeId);
        } catch (Exception e) {
            log.error("Tool execution via LangChain4j failed: {}", e.getMessage(), e);
            return ToolResult.error("LangChain4j tool execution error: " + e.getMessage());
        }
    }
}
