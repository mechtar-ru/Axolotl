package com.agent.orchestrator.service;

import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.Tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {

    @Test
    void testFileWriteBlockedOutsideTargetPath() {
        ToolExecutor executor = new ToolExecutor();
        ToolPermission unrestrictedPerm = new ToolPermission();
        unrestrictedPerm.setEnabled(true);

        Map<String, Object> params = new HashMap<>();
        params.put("path", "/Users/evgenijtihomirov/git/Axolotl/OtherApp/secret.txt");
        params.put("content", "test");

        ToolResult result = executor.handleFileWriteWithSandbox(params, unrestrictedPerm,
                "/Users/evgenijtihomirov/git/Axolotl/MyApp/");

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("blocked"));
    }

    @Test
    void testFileWriteAllowedInsideTargetPath() throws IOException {
        Path tempDir = Files.createTempDirectory("axolotl-test-");
        ToolExecutor executor = new ToolExecutor();
        ToolPermission unrestrictedPerm = new ToolPermission();
        unrestrictedPerm.setEnabled(true);

        Map<String, Object> params = new HashMap<>();
        params.put("path", tempDir.resolve("test.txt").toString());
        params.put("content", "hello");

        ToolResult result = executor.handleFileWriteWithSandbox(params, unrestrictedPerm, tempDir.toString());

        assertTrue(result.isSuccess());
        assertTrue(Files.exists(tempDir.resolve("test.txt")));
        Files.deleteIfExists(tempDir.resolve("test.txt"));
        Files.deleteIfExists(tempDir);
    }

    @Test
    void testFileWriteAllowedWithNodeOverride() throws IOException {
        Path tempDir = Files.createTempDirectory("axolotl-test-");
        Path overrideDir = Files.createTempDirectory("axolotl-override-");
        ToolExecutor executor = new ToolExecutor();

        ToolPermission overridePerm = new ToolPermission();
        overridePerm.setEnabled(true);
        overridePerm.setAllowedPaths(Set.of(overrideDir.toString() + "/**"));

        Map<String, Object> params = new HashMap<>();
        params.put("path", overrideDir.resolve("override.txt").toString());
        params.put("content", "override");

        ToolResult result = executor.handleFileWriteWithSandbox(params, overridePerm, tempDir.toString());

        assertTrue(result.isSuccess());
        Files.deleteIfExists(overrideDir.resolve("override.txt"));
        Files.deleteIfExists(overrideDir);
        Files.deleteIfExists(tempDir);
    }

    @Test
    void testFileWriteOutsideTargetPathWithNodeOverride() throws IOException {
        Path tempDir = Files.createTempDirectory("axolotl-test-");
        ToolExecutor executor = new ToolExecutor();

        ToolPermission overridePerm = new ToolPermission();
        overridePerm.setEnabled(true);
        overridePerm.setAllowedPaths(Set.of("/special/path/**"));

        Map<String, Object> params = new HashMap<>();
        params.put("path", "/outside/anywhere/file.txt");
        params.put("content", "test");

        ToolResult result = executor.handleFileWriteWithSandbox(params, overridePerm, tempDir.toString());

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("blocked") || result.getError().contains("not in node's allowedPaths"));
    }
}
