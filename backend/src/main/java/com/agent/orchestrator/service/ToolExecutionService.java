package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Orchestrates tool execution for agent nodes.
 * Extracted from ExecutionUtilityService to reduce god-class size.
 */
@Service
public class ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

    private final ToolExecutor toolExecutor;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final ToolCallParser toolCallParser;

    public ToolExecutionService(ToolExecutor toolExecutor,
                                ExecutionWebSocketHandler webSocketHandler,
                                ToolCallParser toolCallParser) {
        this.toolExecutor = toolExecutor;
        this.webSocketHandler = webSocketHandler;
        this.toolCallParser = toolCallParser;
    }

    // ────────────────────────── tool definitions ──────────────────────────

    public String buildToolDefinitions(List<String> toolIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("## available_tools\n\n");
        sb.append("namespace functions {\n\n");
        for (String toolId : toolIds) {
            Tool tool = toolExecutor.getTool(toolId);
            if (tool != null) {
                sb.append("// ").append(tool.getDescription()).append("\n");
                sb.append("type ").append(toolId).append(" = (_: {\n");
                sb.append(tool.getInputSchema().replace("\"", "'")).append("\n}) => any;\n\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    // ────────────────────────── tool instructions ──────────────────────────

    public String buildToolInstructions(List<String> toolIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to tools. To use a tool, respond with a JSON object in your final answer.\n\n");
        sb.append("Available tools:\n");
        for (String toolId : toolIds) {
            Tool tool = toolExecutor.getTool(toolId);
            if (tool != null) {
                sb.append("- ").append(toolId).append(": ").append(tool.getDescription()).append("\n");
            }
        }
        if (toolIds.contains("file_write")) {
            sb.append("\nNote: After each file_write, a syntax validator runs automatically. ");
            sb.append("If errors are found, they appear after the write confirmation. ");
            sb.append("Read them carefully and fix the issues in your next iteration.\n");
        }
        if (toolIds.contains("build_app")) {
            sb.append("\nNote: Use build_app after all files are written to check build dependencies ");
            sb.append("and compile the app. It detects missing SDKs and runs the build.\n");
        }
        sb.append("\nTo call a tool, include tool_calls in your response:\n");
        sb.append("```json\n");
        sb.append("{\"role\": \"assistant\", \"content\": \"...\", \"tool_calls\": [");
        sb.append("{\"id\": \"call_1\", \"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}]");
        sb.append("}\n```\n");
        return sb.toString();
    }

    // ────────────────────────── messages for tool call ──────────────────────────

    public String buildMessagesForToolCall(List<Node.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Node.Message msg : messages) {
            sb.append("<message role=\"").append(msg.getRole()).append("\">\n");
            sb.append(msg.getContent()).append("\n</message>\n");
        }
        return sb.toString();
    }

    // ────────────────────────── tool call parsing ──────────────────────────

    public List<Map<String, Object>> parseToolCalls(String response) {
        return toolCallParser.parse(response);
    }

    // ────────────────────────── tool call execution ──────────────────────────

    public String executeToolCall(String toolId, Map<String, Object> args, Node node, String schemaId) {
        return executeToolCall(toolId, args, node, schemaId, null, null);
    }

    public String executeToolCall(String toolId, Map<String, Object> args, Node node, String schemaId, String schemaTargetPath) {
        return executeToolCall(toolId, args, node, schemaId, schemaTargetPath, null);
    }

    public String executeToolCall(String toolId, Map<String, Object> args, Node node, String schemaId,
                                  String schemaTargetPath, String projectType) {
        ToolPermission permission = null;
        if (node.getData().getToolPermissions() != null) {
            for (ToolPermission tp : node.getData().getToolPermissions()) {
                if (tp.getToolId() != null && tp.getToolId().equals(toolId)) {
                    permission = tp;
                    break;
                }
            }
        }

        if (permission == null) {
            List<String> enabledTools = node.getData().getEnabledTools();
            if (enabledTools != null && enabledTools.contains(toolId)) {
                permission = new ToolPermission(toolId);
                permission.setEnabled(true);
            }
        }

        // Inject diff-review flag for file_write when stage config requires it
        if ("file_write".equals(toolId) && node.getData().getConfig() != null
                && Boolean.TRUE.equals(node.getData().getConfig().get("requireDiffReview"))) {
            args.put("_diffReview", true);
        }

        ToolResult result = toolExecutor.execute(toolId, args, permission, schemaId, node.getId(), schemaTargetPath, projectType);
        return result.isSuccess() ? result.getOutput() : "Error: " + result.getError();
    }

    // ────────────────────────── user approval ──────────────────────────

    public void sendUserApprovalRequest(String schemaId, String nodeId, int toolCallCount, int maxToolCalls) {
        if (webSocketHandler != null) {
            webSocketHandler.sendLog(schemaId, "warning",
                    "Достигнут лимит инструментов (" + toolCallCount + "/" + maxToolCalls + "). Требуется подтверждение для продолжения.",
                    nodeId);
        }
    }
}
