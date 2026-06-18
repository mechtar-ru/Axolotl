package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Tool;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.ToolPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class BuildToolHandler {

    private static final Logger log = LoggerFactory.getLogger(BuildToolHandler.class);

    public ToolResult handleBuildApp(Map<String, Object> params, ToolPermission permission,
                                      String schemaTargetPath) {
        String projectPath = (String) params.get("projectPath");
        if (projectPath == null || projectPath.isBlank()) {
            projectPath = schemaTargetPath;
        }
        if (projectPath == null || projectPath.isBlank()) {
            return ToolResult.error("No project path provided and no schema target path set");
        }

        StringBuilder report = new StringBuilder();
        report.append("# Build Report for ").append(projectPath).append("\n\n");
        List<String> missingSdks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // Check if project directory exists
        Path projectDir = Path.of(projectPath);
        if (!Files.exists(projectDir)) {
            report.append("## ❌ Project directory not found\n");
            report.append("Directory `").append(projectPath).append("` does not exist.\n");
            report.append("\n## Suggested fix:\n");
            report.append("```bash\nflutter create --project-name ").append(projectDir.getFileName()).append(" ").append(projectPath).append("\n```\n");
            return ToolResult.ok(report.toString());
        }

        // Check for existing project files
        boolean hasPubspec = Files.exists(projectDir.resolve("pubspec.yaml"));
        boolean hasBuildGradle = Files.exists(projectDir.resolve("build.gradle"))
                || Files.exists(projectDir.resolve("build.gradle.kts"));
        boolean hasPackageJson = Files.exists(projectDir.resolve("package.json"));
        boolean hasCargoToml = Files.exists(projectDir.resolve("Cargo.toml"));
        boolean hasGoMod = Files.exists(projectDir.resolve("go.mod"));
        boolean hasSetupPy = Files.exists(projectDir.resolve("setup.py"))
                || Files.exists(projectDir.resolve("pyproject.toml"));

        // Detect project type
        String detectedType = "unknown";
        if (hasPubspec) detectedType = "flutter";
        else if (hasBuildGradle) detectedType = "android/gradle";
        else if (hasPackageJson) detectedType = "node";
        else if (hasCargoToml) detectedType = "rust";
        else if (hasGoMod) detectedType = "go";
        else if (hasSetupPy) detectedType = "python";

        report.append("## Project Health\n");
        report.append("- **Type:** ").append(detectedType).append("\n");

        // Check SDK availability
        checkSdk(report, missingSdks, "flutter", "Flutter SDK");
        checkSdk(report, missingSdks, "dart", "Dart SDK");
        checkSdk(report, missingSdks, "java", "Java JDK");
        checkSdk(report, missingSdks, "node", "Node.js");
        checkSdk(report, missingSdks, "npm", "npm");
        if (hasCargoToml) {
            checkSdk(report, missingSdks, "cargo", "Cargo/Rust");
        }
        if (hasGoMod) {
            checkSdk(report, missingSdks, "go", "Go");
        }
        if (hasSetupPy) {
            checkSdk(report, missingSdks, "python3", "Python 3");
        }

        // Run flutter pub get for Flutter projects
        if (hasPubspec) {
            report.append("\n## Dependencies\n");
            try {
                ProcessBuilder pb = new ProcessBuilder("flutter", "pub", "get")
                        .directory(projectDir.toFile())
                        .redirectErrorStream(true);
                Process proc = pb.start();
                String pubOut = new String(proc.getInputStream().readAllBytes());
                boolean pubOk = proc.waitFor(60, TimeUnit.SECONDS);
                if (!pubOk) {
                    proc.destroyForcibly();
                }
                if (pubOk && proc.exitValue() == 0) {
                    report.append("- ✅ `flutter pub get` succeeded\n");
                } else {
                    report.append("- ❌ `flutter pub get` failed:\n");
                    report.append("  ```\n").append(pubOut).append("\n  ```\n");
                    warnings.add("pub get");
                }
            } catch (Exception e) {
                report.append("- ❌ `flutter pub get` errored: ").append(e.getMessage()).append("\n");
            }
        }

        // Run dart analyze for Dart/Flutter projects
        if (hasPubspec) {
            report.append("\n## Static Analysis\n");
            try {
                ProcessBuilder pb = new ProcessBuilder("dart", "analyze", ".")
                        .directory(projectDir.toFile())
                        .redirectErrorStream(true);
                Process proc = pb.start();
                String analysisOut = new String(proc.getInputStream().readAllBytes()).trim();
                boolean analyzed = proc.waitFor(60, TimeUnit.SECONDS);
                if (!analyzed) {
                    proc.destroyForcibly();
                }
                if (analyzed && proc.exitValue() == 0) {
                    report.append("- ✅ `dart analyze` — no issues found\n");
                } else {
                    report.append("- ⚠️  `dart analyze` found issues:\n");
                    if (!analysisOut.isEmpty()) {
                        report.append("  ```\n").append(analysisOut).append("\n  ```\n");
                    }
                }
            } catch (Exception e) {
                report.append("- ❌ `dart analyze` errored: ").append(e.getMessage()).append("\n");
            }
        }

        // Run flutter test if test directory exists
        if (hasPubspec && Files.exists(projectDir.resolve("test"))) {
            report.append("\n## Tests\n");
            try {
                ProcessBuilder pb = new ProcessBuilder("flutter", "test")
                        .directory(projectDir.toFile())
                        .redirectErrorStream(true);
                Process proc = pb.start();
                String testOut = new String(proc.getInputStream().readAllBytes()).trim();
                boolean tested = proc.waitFor(120, TimeUnit.SECONDS);
                if (!tested) {
                    proc.destroyForcibly();
                }
                if (tested && proc.exitValue() == 0) {
                    report.append("- ✅ `flutter test` passed\n");
                } else {
                    report.append("- ⚠️  `flutter test` had issues:\n");
                    if (!testOut.isEmpty()) {
                        report.append("  ```\n").append(testOut).append("\n  ```\n");
                    }
                }
            } catch (Exception e) {
                report.append("- ❌ `flutter test` errored: ").append(e.getMessage()).append("\n");
            }
        }

        // Summary
        report.append("\n## Summary\n");
        if (missingSdks.isEmpty()) {
            report.append("- ✅ All required SDKs are installed\n");
        } else {
            report.append("- ❌ Missing SDKs: ").append(String.join(", ", missingSdks)).append("\n");
        }
        if (warnings.isEmpty()) {
            report.append("- ✅ No warnings\n");
        } else {
            report.append("- ⚠️  Warnings: ").append(String.join(", ", warnings)).append("\n");
        }

        return ToolResult.ok(report.toString());
    }

    private void checkSdk(StringBuilder details, List<String> missing, String cmd, String label) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                details.append("- ❌ ").append(label).append(": check timed out\n");
                missing.add(label);
                return;
            }
            boolean found = proc.exitValue() == 0;
            if (found) {
                details.append("- ✅ ").append(label).append(": installed\n");
            } else {
                details.append("- ❌ ").append(label).append(": not found\n");
                missing.add(label);
            }
        } catch (Exception e) {
            details.append("- ❌ ").append(label).append(": check failed (").append(e.getMessage()).append(")\n");
            missing.add(label);
        }
    }
}
