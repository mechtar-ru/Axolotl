package com.agent.orchestrator.service;

import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the auto-fix pass for FLUTTER projects after agent execution.
 * Runs dart analyze, calls an LLM fix model to resolve errors, and repeats
 * up to 3 attempts.
 */
@Service
public class FixPassOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FixPassOrchestrator.class);

    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final FlutterScaffoldHelper flutterScaffoldHelper;

    public FixPassOrchestrator(LlmService llmService,
                               ExecutionWebSocketHandler webSocketHandler,
                               FlutterScaffoldHelper flutterScaffoldHelper) {
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.flutterScaffoldHelper = flutterScaffoldHelper;
    }

    /**
     * Run fix pass for FLUTTER project. Checks dart analyze,
     * calls LLM to fix errors up to 3 attempts.
     *
     * @param targetDir  the project directory
     * @param nodeId     node ID for WebSocket events
     * @param schemaId   schema ID for model resolution
     * @param schemaName schema name (unused currently, reserved for future use)
     * @return FixPassResult with count of errors remaining, errors list, and per-attempt messages
     */
    public FixPassResult runFixPass(String targetDir, String nodeId, String schemaId, String schemaName) {
        // Resolve fix model (null-safe — default candidates if no node context)
        String fixModel = flutterScaffoldHelper.resolveFlutterFixModel(null);
        if (fixModel == null) {
            return new FixPassResult(0, List.of(), List.of());
        }

        List<String> errors = new ArrayList<>();
        List<String> fixMessages = new ArrayList<>();
        int errorsRemaining = 0;

        for (int attempt = 1; attempt <= 3; attempt++) {
            String analyzeOut = flutterScaffoldHelper.runDartAnalyze(targetDir);
            if (analyzeOut == null || analyzeOut.isBlank()
                    || analyzeOut.contains("No issues found")
                    || analyzeOut.contains("no issues found")) {
                if (attempt > 1) {
                    fixMessages.add("[FIX PASS] All errors resolved in " + attempt + " attempts");
                }
                break;
            }

            long errCount = analyzeOut.lines().filter(l -> l.contains("error -")).count();
            if (errCount == 0) {
                break; // only info-level issues remain, no compilation errors
            }

            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "info",
                        "Auto-fix attempt " + attempt + ": " + errCount + " Dart errors", nodeId);
            }

            // Collect current file contents for context
            StringBuilder fileContext = new StringBuilder();
            try {
                Files.walk(Paths.get(targetDir, "lib"), 5)
                        .filter(f -> f.toString().endsWith(".dart"))
                        .forEach(f -> {
                            try {
                                fileContext.append("\n--- ")
                                        .append(Paths.get(targetDir).relativize(f))
                                        .append(" ---\n");
                                fileContext.append(Files.readString(f));
                            } catch (Exception e) { /* skip unreadable files */ }
                        });
            } catch (Exception e) { /* skip walk failure */ }

            String fixPrompt = "Fix these Dart compilation errors. Return each FIXED file with marker:\n"
                    + "--- lib/path/to/file.dart ---\n[complete fixed content]\n"
                    + "Errors:\n" + analyzeOut
                    + "\n\nCurrent files:\n" + fileContext;

            LlmResponse fixResp = llmService.chat(fixModel,
                    "You are a Dart/Flutter fixer. Return ONLY fixed file content with --- path --- markers.",
                    fixPrompt, null);

            if (fixResp != null && fixResp.text() != null) {
                int fixed = flutterScaffoldHelper.applyMarkedFiles(targetDir, fixResp.text());
                if (fixed > 0) {
                    flutterScaffoldHelper.runBash(targetDir, "flutter pub get", 60);
                    fixMessages.add("[FIX PASS] Attempt " + attempt + ": fixed " + fixed + " files");
                }
            }
        }

        // Final analyze to determine remaining errors
        String finalAnalyze = flutterScaffoldHelper.runDartAnalyze(targetDir);
        if (finalAnalyze != null) {
            errorsRemaining = (int) finalAnalyze.lines().filter(l -> l.contains("error -")).count();
            // Collect error details
            finalAnalyze.lines()
                    .filter(l -> l.contains("error -"))
                    .forEach(l -> {
                        if (errors.size() < 20) { // cap error detail collection
                            errors.add(l.trim());
                        }
                    });
        }

        return new FixPassResult(errorsRemaining, errors, fixMessages);
    }

    /**
     * Result of the fix pass execution.
     *
     * @param errorsRemaining number of errors remaining after fix pass
     * @param errors          list of error details (capped at 20)
     * @param fixedFiles      list of per-attempt fix messages (e.g. "[FIX PASS] Attempt 1: fixed 3 files")
     */
    public record FixPassResult(int errorsRemaining, List<String> errors, List<String> fixedFiles) {}
}
