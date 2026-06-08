package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolCategory;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.ToolPermission;

import java.util.List;
import java.util.Map;

public interface ToolExecutor {

    void registerTool(Tool tool);

    void registerPluginHandler(String toolId, ToolExecutorHandler handler);

    Tool getTool(String toolId);

    Map<String, Tool> getAllTools();

    List<Tool> getToolsByCategory(ToolCategory category);

    ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission);

    ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission, String schemaId, String nodeId);

    ToolResult handleFileReadWithSandbox(Map<String, Object> params, ToolPermission permission, String schemaTargetPath);

    ToolResult handleFileWriteWithSandbox(Map<String, Object> params, ToolPermission permission,
                                          String schemaTargetPath, String schemaId, String nodeId);

    ToolResult handleDirectoryReadWithSandbox(Map<String, Object> params, ToolPermission permission, String schemaTargetPath);

    ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission,
                       String schemaId, String nodeId, String schemaTargetPath);

    ToolResult execute(String toolId, Map<String, Object> params, ToolPermission permission,
                       String schemaId, String nodeId, String schemaTargetPath,
                       String projectTypeStr);

    @FunctionalInterface
    interface ToolExecutorHandler {
        ToolResult execute(Map<String, Object> params, ToolPermission permission);
    }
}
