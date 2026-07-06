package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Post-processing for agent execution: file change summary, build verification, FLUTTER deps.
 * Extracted from AgentNodeStrategy to reduce class size.
 */
@Component
public class AgentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AgentPostProcessor.class);

    private final ExecutionWebSocketHandler webSocketHandler;
    private final ExecutionStateManager stateManager;
    private final FlutterScaffoldHelper flutterScaffoldHelper;
    private final FixPassOrchestrator fixPassOrchestrator;

    public AgentPostProcessor(ExecutionWebSocketHandler webSocketHandler,
                              ExecutionStateManager stateManager,
                              FlutterScaffoldHelper flutterScaffoldHelper,
                              FixPassOrchestrator fixPassOrchestrator) {
        this.webSocketHandler = webSocketHandler;
        this.stateManager = stateManager;
        this.flutterScaffoldHelper = flutterScaffoldHelper;
        this.fixPassOrchestrator = fixPassOrchestrator;
    }

    /**
     * Post-process agent output: collect file changes, verify, build, FLUTTER auto-fix.
     */
    public String postProcessToolAgent(Node node, WorkflowSchema schema, String schemaId, String finalResponse) {
        if (finalResponse == null || finalResponse.isBlank()) return finalResponse;

        List<Map<String, String>> writtenFiles = collectWrittenFiles(schemaId, node.getId());
        if (!writtenFiles.isEmpty()) {
            stateManager.getGeneratedFilesRegistry().put(schemaId + ":" + node.getId(), writtenFiles);
        }

        if (webSocketHandler != null && !writtenFiles.isEmpty()) {
            String filesJson = writtenFiles.stream()
                    .map(f -> "{\"path\":\"" + f.get("path") + "\",\"action\":\"" + f.get("action") + "\"}")
                    .collect(Collectors.joining(",", "[", "]"));
            webSocketHandler.sendLiveUpdate(schemaId, "generated_files",
                    Map.of("nodeId", node.getId(), "files", filesJson));
        }

        // Log warning if no files generated
        if (webSocketHandler != null && writtenFiles.isEmpty() && schema != null
                && schema.getTargetPath() != null) {
            webSocketHandler.sendLog(schemaId, "warning",
                    "Zero file_write calls detected — no files generated", node.getId());
        }

        // FLUTTER: ensure scaffold, install deps, analyze, fix
        if (schema != null && "FLUTTER".equals(schema.getAppType()) && schema.getTargetPath() != null) {
            String targetPath = schema.getTargetPath();
            flutterScaffoldHelper.ensureFlutterScaffold(targetPath);
            flutterScaffoldHelper.runBash(targetPath, List.of("flutter", "pub", "get"), 120);
            String analyzeResult = flutterScaffoldHelper.runDartAnalyze(targetPath);
            if (analyzeResult != null && analyzeResult.contains("error")) {
                log.info("Dart analyze found errors in {} after agent execution", targetPath);
                fixPassOrchestrator.runFixPass(targetPath, node.getId(), schemaId, schema.getName());
            }
        }

        return finalResponse;
    }

    /**
     * Collect files written during this node's execution.
     */
    public List<Map<String, String>> collectWrittenFiles(String schemaId, String nodeId) {
        Map<String, String> changes = stateManager.getFileChanges(schemaId, nodeId);
        if (changes == null || changes.isEmpty()) return List.of();
        List<Map<String, String>> files = new ArrayList<>();
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            files.add(Map.of("path", entry.getKey(), "action", entry.getValue()));
        }
        return files;
    }
}
