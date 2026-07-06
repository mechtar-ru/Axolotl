package com.agent.orchestrator.service;

import com.agent.orchestrator.model.WorkflowSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utility for extracting code blocks from LLM responses and saving them as files.
 * Used by AgentNodeStrategy (auto-save) and VerifierNodeStrategy (generated files).
 */
@Component
public class CodeBlockSaver {

    private static final Logger log = LoggerFactory.getLogger(CodeBlockSaver.class);

    /** Regex to match ```path\n...\n``` code blocks */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```([^\\n]+)\\n?(.*?)```", Pattern.DOTALL);

    /**
     * Extract and save all ```path\n...\n``` code blocks from the response text.
     * Each block is saved as a file under the schema's targetPath.
     *
     * @param response    The LLM response text containing code blocks
     * @param targetPath  The base directory to save files in
     * @param schemaId    Schema ID (for logging)
     * @param nodeId      Node ID (for logging)
     * @return List of saved file paths (relative to targetPath)
     */
    public List<String> saveCodeBlocks(String response, String targetPath, String schemaId, String nodeId) {
        List<String> saved = new ArrayList<>();
        if (response == null || response.isBlank() || targetPath == null || targetPath.isBlank()) {
            return saved;
        }

        Matcher matcher = CODE_BLOCK_PATTERN.matcher(response);
        while (matcher.find()) {
            String filePath = matcher.group(1).trim();
            String code = matcher.group(2).trim();
            if (code.isEmpty() || filePath.isEmpty() || !filePath.contains(".")) continue;

            try {
                Path fullPath = Path.of(targetPath, filePath).normalize();
                Files.createDirectories(fullPath.getParent());
                Files.writeString(fullPath, code);
                log.info("Saved code block: {} ({} bytes) for {}/{}", fullPath, code.length(), schemaId, nodeId);
                saved.add(filePath);
            } catch (Exception e) {
                log.warn("Failed to save code block {}: {}", filePath, e.getMessage());
            }
        }
        return saved;
    }

    /**
     * Check if the response text contains any code blocks.
     */
    public boolean hasCodeBlocks(String response) {
        if (response == null || response.isBlank()) return false;
        return CODE_BLOCK_PATTERN.matcher(response).find();
    }

    /**
     * Extract a file path from the source node's input data or system prompt.
     * Looks for patterns like "snake/snake.py" or "write to path/file.py".
     */
    public String extractFilePathFromNodeData(WorkflowSchema ws) {
        if (ws == null || ws.getNodes() == null) return null;
        Pattern pathPattern = Pattern.compile(
                "(?:write\\s+(?:to\\s+)?|save\\s+(?:as\\s+)?|create\\s+)?([a-zA-Z0-9_./-]+\\.[a-zA-Z0-9]+)",
                Pattern.CASE_INSENSITIVE);
        for (com.agent.orchestrator.model.Node node : ws.getNodes()) {
            if (node == null || node.getData() == null) continue;
            for (String text : new String[]{node.getData().getSourceData(), node.getData().getSystemPrompt()}) {
                if (text == null || text.isBlank()) continue;
                Matcher m = pathPattern.matcher(text);
                if (m.find()) {
                    String path = m.group(1).trim();
                    if (path.contains(".") && !path.startsWith("fix_")) {
                        return path;
                    }
                }
            }
        }
        return null;
    }
}
