package com.agent.orchestrator.service;

import com.agent.orchestrator.config.AppConfig;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Service
public class FlutterScaffoldHelper {

    private static final Logger log = LoggerFactory.getLogger(FlutterScaffoldHelper.class);

    private final AppConfig appConfig;
    private final LlmService llmService;

    public FlutterScaffoldHelper(AppConfig appConfig, LlmService llmService) {
        this.appConfig = appConfig;
        this.llmService = llmService;
    }

    /**
     * Run flutter create if targetPath doesn't have a pubspec.yaml yet.
     * Returns true if scaffold was created or already exists.
     */
    public boolean ensureFlutterScaffold(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) return false;
        Path dir = Path.of(targetPath);
        if (!Files.exists(dir.resolve("pubspec.yaml"))) {
            try {
                log.info("Auto-scaffolding FLUTTER project in {}", targetPath);
                Files.createDirectories(dir);
                ProcessBuilder fb = new ProcessBuilder("flutter", "create",
                        "--project-name", new java.io.File(targetPath).getName(),
                        "--platforms", "android,ios,macos",
                        targetPath);
                fb.redirectErrorStream(true);
                Process fp = fb.start();
                fp.waitFor(120, TimeUnit.SECONDS);
                log.info("flutter create exited with {}", fp.exitValue());
                Path mainDart = dir.resolve("lib/main.dart");
                log.info("Scaffold created, main.dart exists: {}", Files.exists(mainDart));
                return fp.exitValue() == 0;
            } catch (Exception e) {
                log.warn("Auto-scaffold failed (non-fatal): {}", e.getMessage());
                return false;
            }
        }
        return true;
    }

    /**
     * Resolve the best available Flutter fix model.
     * Prefers qwen2.5-coder:14b, falls back to 7b, then null.
     */
    public String resolveFlutterFixModel(Node node) {
        // Prefer 14B for fixes, fallback to executor model
        String[] candidates = {"ollama:qwen2.5-coder:14b-instruct-q4_K_M", "ollama:qwen2.5-coder:7b-instruct-q4_K_M"};
        if (node != null && node.getData() != null) {
            String executorModel = node.getData().getModel();
            if (executorModel != null && !executorModel.isBlank()) {
                candidates = new String[]{executorModel, "ollama:qwen2.5-coder:14b-instruct-q4_K_M"};
            }
        }
        for (String m : candidates) {
            try {
                String testResp = llmService.chat(m, "You are a test. Reply 'ok'.", "Reply 'ok'.", null).text();
                if (testResp != null) return m;
            } catch (Exception e) { /* try next */ }
        }
        return null;
    }

    /**
     * Run dart analyze on targetDir and return the output.
     */
    public String runDartAnalyze(String targetDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c",
                    "cd " + targetDir + " && dart analyze lib/ 2>&1");
            Process p = pb.start();
            if (p.waitFor(120, TimeUnit.SECONDS)) {
                return new String(p.getInputStream().readAllBytes());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Run a bash command in the given directory with timeout.
     */
    public void runBash(String dir, String cmd, int timeoutSec) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", "cd " + dir + " && " + cmd);
            Process p = pb.start();
            p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("bash command failed: {} (dir={}, cmd={})", e.getMessage(), dir, cmd);
        }
    }

    /**
     * Apply marked files from agent output. Parses --- markers.
     */
    public int applyMarkedFiles(String targetDir, String content) {
        int count = 0;
        try {
            String[] parts = content.split("(?m)^--- ");
            for (String part : parts) {
                if (part.isBlank()) continue;
                int nlIdx = part.indexOf('\n');
                if (nlIdx < 0) continue;
                String relPath = part.substring(0, nlIdx).trim().replace("```", "").trim();
                String fileContent = part.substring(nlIdx + 1).trim();
                // Remove trailing ``` if present (from markdown code blocks)
                if (fileContent.endsWith("```")) {
                    fileContent = fileContent.substring(0, fileContent.length() - 3).trim();
                }
                if (relPath.contains("..") || relPath.startsWith("/")) continue; // safety check
                Path fullPath = Paths.get(targetDir, relPath);
                Files.createDirectories(fullPath.getParent());
                Files.writeString(fullPath, fileContent);
                count++;
            }
        } catch (Exception e) {
            log.warn("Error applying fix files: {}", e.getMessage());
        }
        return count;
    }

    /**
     * Check if a target path needs Flutter scaffold (pubspec.yaml is missing).
     */
    public boolean needsFlutterScaffold(String targetPath) {
        if (targetPath == null || targetPath.isBlank()) return false;
        return !Files.exists(Paths.get(targetPath, "pubspec.yaml"));
    }
}
