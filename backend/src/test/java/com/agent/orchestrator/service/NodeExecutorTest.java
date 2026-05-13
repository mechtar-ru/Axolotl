package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.*;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class NodeExecutorTest {

    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock ToolExecutor toolExecutor;
    @Mock TransformService transformService;
    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock PlanService planService;
    @Mock ProjectContextBuilder projectContextBuilder;

    NodeExecutor nodeExecutor;

    @BeforeEach
    void setUp() {
        nodeExecutor = new NodeExecutor(llmService, webSocketHandler, memPalaceClient,
                toolExecutor, transformService, schemaRepository, planService, projectContextBuilder);
    }

    // === Command Sanitization ===

    @Test
    void sanitizeCommand_blocksRmRf() {
        assertThrows(SecurityException.class,
                () -> nodeExecutor.sanitizeCommandPublic("rm -rf /"));
    }

    @Test
    void sanitizeCommand_blocksMkfs() {
        assertThrows(SecurityException.class,
                () -> nodeExecutor.sanitizeCommandPublic("mkfs.ext4 /dev/sda1"));
    }

    @Test
    void sanitizeCommand_blocksShutdown() {
        assertThrows(SecurityException.class,
                () -> nodeExecutor.sanitizeCommandPublic("shutdown -h now"));
    }

    @Test
    void sanitizeCommand_blocksDangerousExpansion() {
        assertThrows(SecurityException.class,
                () -> nodeExecutor.sanitizeCommandPublic("echo $(rm -rf /)"));
    }

    @Test
    void sanitizeCommand_allowsSafeCommands() {
        assertEquals("ls -la", nodeExecutor.sanitizeCommandPublic("ls -la"));
        assertEquals("echo hello", nodeExecutor.sanitizeCommandPublic("echo hello"));
        assertEquals("cat file.txt", nodeExecutor.sanitizeCommandPublic("cat file.txt"));
    }

    // === URL Validation ===

    @Test
    void validateUrl_blocksFtp() {
        assertThrows(SecurityException.class,
                () -> nodeExecutor.validateUrlPublic("ftp://example.com/file"));
    }

    @Test
    void validateUrl_blocksJavascript() {
        assertThrows(SecurityException.class,
                () -> nodeExecutor.validateUrlPublic("javascript:alert(1)"));
    }

    @Test
    void validateUrl_blocksLocalhost() {
        assertThrows(SecurityException.class,
                () -> nodeExecutor.validateUrlPublic("http://localhost:8080/api"));
    }

    @Test
    void validateUrl_blocksInternalIp() {
        assertThrows(SecurityException.class,
                () -> nodeExecutor.validateUrlPublic("http://192.168.1.1/admin"));
    }

    @Test
    void validateUrl_blocksNoHost() {
        // "http://" has no host — throws IllegalArgumentException from URI.create or SecurityException
        assertThrows(Exception.class,
                () -> nodeExecutor.validateUrlPublic("http://"));
    }

    // === Path Validation ===

    @Test
    void isPathAllowed_noRestrictions_returnsTrue() {
        // Default config allows all paths
        assertTrue(nodeExecutor.isPathAllowedPublic("/any/path/file.txt"));
    }

    // === Condition Evaluation ===

    @Test
    void evaluateCondition_trueLiteral() {
        assertTrue(nodeExecutor.evaluateConditionPublic("true", Map.of()));
    }

    @Test
    void evaluateCondition_falseLiteral() {
        assertFalse(nodeExecutor.evaluateConditionPublic("false", Map.of()));
    }

    @Test
    void evaluateCondition_comparison() {
        assertTrue(nodeExecutor.evaluateConditionPublic("5 > 3", Map.of()));
        assertFalse(nodeExecutor.evaluateConditionPublic("5 < 3", Map.of()));
    }

    @Test
    void evaluateCondition_withVariables() {
        Map<String, Object> ctx = Map.of("x", 10, "y", 20);
        assertTrue(nodeExecutor.evaluateConditionPublic("x < y", ctx));
    }

    @Test
    void evaluateCondition_nullExpression() {
        assertFalse(nodeExecutor.evaluateConditionPublic(null, Map.of()));
    }

    @Test
    void evaluateCondition_blankExpression() {
        assertFalse(nodeExecutor.evaluateConditionPublic("  ", Map.of()));
    }

    @Test
    void evaluateCondition_invalidExpression() {
        assertFalse(nodeExecutor.evaluateConditionPublic("<<<not valid>>>", Map.of()));
    }

    // === Variable Interpolation ===

    @Test
    void interpolateVariables_input() {
        WorkflowSchema s = new WorkflowSchema();
        s.setNodes(List.of());
        s.setEdges(List.of());
        Map<String, Object> preds = Map.of("Source", "hello world");
        String result = nodeExecutor.interpolateVariablesPublic("Process: {{input}}", s, preds);
        assertEquals("Process: hello world", result);
    }

    @Test
    void interpolateVariables_prevResult() {
        WorkflowSchema s = new WorkflowSchema();
        s.setNodes(List.of());
        s.setEdges(List.of());
        Map<String, Object> preds = new LinkedHashMap<>();
        preds.put("First", "data1");
        preds.put("Second", "data2");
        String result = nodeExecutor.interpolateVariablesPublic("Use: {{prev_result}}", s, preds);
        assertEquals("Use: data2", result);
    }

    @Test
    void interpolateVariables_schemaName() {
        WorkflowSchema s = new WorkflowSchema();
        s.setName("My Workflow");
        s.setNodes(List.of());
        s.setEdges(List.of());
        String result = nodeExecutor.interpolateVariablesPublic("Workflow: {{schema_name}}", s, Map.of());
        assertEquals("Workflow: My Workflow", result);
    }

    @Test
    void interpolateVariables_noVariables() {
        WorkflowSchema s = new WorkflowSchema();
        s.setNodes(List.of());
        s.setEdges(List.of());
        String result = nodeExecutor.interpolateVariablesPublic("No vars here", s, Map.of());
        assertEquals("No vars here", result);
    }

    @Test
    void interpolateVariables_nullInput() {
        WorkflowSchema s = new WorkflowSchema();
        s.setNodes(List.of());
        s.setEdges(List.of());
        assertNull(nodeExecutor.interpolateVariablesPublic(null, s, Map.of()));
    }

    // === Output Writing ===

    @Test
    void writeOutput_logType_returnsContent() {
        assertEquals("hello from agent",
                nodeExecutor.writeOutputPublic("log", null, "text", "hello from agent"));
    }

    @Test
    void writeOutput_nullContent_returnsNoData() {
        assertEquals("Нет данных для вывода",
                nodeExecutor.writeOutputPublic("log", null, "text", null));
    }

    @Test
    void writeOutput_fileType_writesToDisk() throws Exception {
        Path tempDir = Files.createTempDirectory("axolotl-test");
        Path filePath = tempDir.resolve("output.md");
        try {
            nodeExecutor.writeOutputPublic("file", filePath.toString(), "markdown", "result content");
            assertEquals("result content", Files.readString(filePath));
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void writeOutput_fileType_createsParentDirs() throws Exception {
        Path tempDir = Files.createTempDirectory("axolotl-test");
        Path filePath = tempDir.resolve("sub/dir/output.txt");
        try {
            nodeExecutor.writeOutputPublic("file", filePath.toString(), "text", "nested content");
            assertTrue(Files.exists(filePath));
            assertEquals("nested content", Files.readString(filePath));
        } finally {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    void writeOutput_fileType_json_wrapsResult() throws Exception {
        Path tempDir = Files.createTempDirectory("axolotl-test");
        Path filePath = tempDir.resolve("output.json");
        try {
            nodeExecutor.writeOutputPublic("file", filePath.toString(), "json", "some text");
            String fileContent = Files.readString(filePath);
            assertTrue(fileContent.contains("\"result\""));
            assertTrue(fileContent.contains("some text"));
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(tempDir);
        }
    }

    // === Context Block ===

    @Test
    void buildContextBlock_shortContext() {
        Map<String, Object> preds = Map.of("Source", "Short data");
        String result = nodeExecutor.buildContextBlockPublic(preds);
        assertTrue(result.contains("[Source]: Short data"));
    }

    @Test
    void buildContextBlock_emptyInput() {
        assertEquals("", nodeExecutor.buildContextBlockPublic(Map.of()));
    }

    // === Sleep with Cancel ===

    @Test
    void sleepWithCancel_returnsFalseWhenCancelled() {
        assertFalse(nodeExecutor.sleepWithCancelPublic(100, new java.util.concurrent.atomic.AtomicBoolean(true)));
    }

    @Test
    void sleepWithCancel_returnsTrueWhenNotCancelled() {
        assertTrue(nodeExecutor.sleepWithCancelPublic(50, new java.util.concurrent.atomic.AtomicBoolean(false)));
    }
}
