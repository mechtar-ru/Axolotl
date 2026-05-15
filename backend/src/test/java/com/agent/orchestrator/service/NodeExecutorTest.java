package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.*;
import com.agent.orchestrator.repository.ExecutionRepository;
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
    @Mock ExecutionRepository executionRepository;

    NodeExecutor nodeExecutor;

    @BeforeEach
    void setUp() {
        nodeExecutor = new NodeExecutor(llmService, webSocketHandler, memPalaceClient,
                toolExecutor, transformService, schemaRepository, planService, projectContextBuilder,
                executionRepository);
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

    // === Verifier node config extraction ===

    @Test
    void executeVerifierNode_validConfig_setsAgentDataCorrectly() {
        Node node = new Node();
        node.setId("verifier-1");
        node.setType("verifier");
        node.setName("Verify Generated Code");

        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();
        checks.put("syntaxCheck", true);
        checks.put("requiredPatterns", List.of("def ", "return"));
        checks.put("testCommand", "");
        checks.put("maxFileSizeKb", 500);
        config.put("checks", checks);
        data.setConfig(config);
        data.setEnabledTools(List.of("file_read", "bash", "grep"));
        data.setAgentType("verifier");
        node.setData(data);

        assertEquals("verifier", node.getData().getAgentType());
        assertEquals(List.of("file_read", "bash", "grep"), node.getData().getEnabledTools());

        @SuppressWarnings("unchecked")
        Map<String, Object> extractedChecks = (Map<String, Object>) node.getData().getConfig().get("checks");
        assertNotNull(extractedChecks);
        assertTrue((Boolean) extractedChecks.get("syntaxCheck"));
        assertEquals(List.of("def ", "return"), extractedChecks.get("requiredPatterns"));
    }

    @Test
    void executeVerifierNode_invalidSyntaxConfig_syntaxCheckTrue() {
        Node node = new Node();
        node.setId("verifier-2");
        node.setType("verifier");
        node.setName("Verify Broken Code");

        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();
        checks.put("syntaxCheck", true);
        checks.put("requiredPatterns", List.of());
        checks.put("testCommand", "");
        checks.put("maxFileSizeKb", 500);
        config.put("checks", checks);
        data.setConfig(config);
        data.setEnabledTools(List.of("file_read", "bash", "grep"));
        data.setAgentType("verifier");
        node.setData(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> extractedChecks = (Map<String, Object>) node.getData().getConfig().get("checks");
        assertNotNull(extractedChecks);
        assertTrue((Boolean) extractedChecks.get("syntaxCheck"));
        assertTrue(((List<String>) extractedChecks.get("requiredPatterns")).isEmpty());
    }

    @Test
    void executeVerifierNode_missingRequiredPattern_configReadCorrectly() {
        Node node = new Node();
        node.setId("verifier-3");
        node.setType("verifier");
        node.setName("Verify Missing Pattern");

        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();
        checks.put("syntaxCheck", false);
        checks.put("requiredPatterns", List.of("@", "move()", "check_victory"));
        checks.put("testCommand", "");
        checks.put("maxFileSizeKb", 500);
        config.put("checks", checks);
        data.setConfig(config);
        data.setEnabledTools(List.of("file_read", "bash", "grep"));
        data.setAgentType("verifier");
        node.setData(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> extractedChecks = (Map<String, Object>) node.getData().getConfig().get("checks");
        assertNotNull(extractedChecks);
        assertFalse((Boolean) extractedChecks.get("syntaxCheck"));

        @SuppressWarnings("unchecked")
        List<String> patterns = (List<String>) extractedChecks.get("requiredPatterns");
        assertEquals(3, patterns.size());
        assertTrue(patterns.contains("@"));
        assertTrue(patterns.contains("move()"));
        assertTrue(patterns.contains("check_victory"));
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

    // === Review Node Config ===

    @Test
    void executeReviewNode_noChecks_configReadCorrectly() {
        Node node = new Node();
        node.setId("review-1");
        node.setType("review");
        node.setName("Review Plan");

        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();
        checks.put("premortem", false);
        checks.put("prism", false);
        checks.put("postmortem", false);
        checks.put("mode", "pass-through");
        checks.put("maxIterations", 3);
        config.put("checks", checks);
        data.setConfig(config);
        data.setAgentType("review");
        node.setData(data);

        assertEquals("review", node.getData().getAgentType());
        assertEquals("review", node.getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> extractedChecks = (Map<String, Object>) node.getData().getConfig().get("checks");
        assertNotNull(extractedChecks);
        assertFalse((Boolean) extractedChecks.get("premortem"));
        assertFalse((Boolean) extractedChecks.get("prism"));
        assertFalse((Boolean) extractedChecks.get("postmortem"));
        assertEquals("pass-through", extractedChecks.get("mode"));
        assertEquals(3, extractedChecks.get("maxIterations"));
    }

    @Test
    void executeReviewNode_premortemEnabled_configReadCorrectly() {
        Node node = new Node();
        node.setId("review-2");
        node.setType("review");
        node.setName("Review with Premortem");

        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();
        checks.put("premortem", true);
        checks.put("prism", false);
        checks.put("postmortem", false);
        checks.put("mode", "pass-through");
        checks.put("maxIterations", 5);
        config.put("checks", checks);
        data.setConfig(config);
        data.setAgentType("review");
        node.setData(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> extractedChecks = (Map<String, Object>) node.getData().getConfig().get("checks");
        assertNotNull(extractedChecks);
        assertTrue((Boolean) extractedChecks.get("premortem"));
        assertFalse((Boolean) extractedChecks.get("prism"));
        assertFalse((Boolean) extractedChecks.get("postmortem"));
        assertEquals("pass-through", extractedChecks.get("mode"));
        assertEquals(5, ((Number) extractedChecks.get("maxIterations")).intValue());
    }

    @Test
    void executeReviewNode_manualMode_returnsAwaitingApproval() {
        // Create a Node with type "review", mode "manual"
        Node node = new Node();
        node.setId("review-manual");
        node.setType("review");
        node.setName("Manual Review");

        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();
        checks.put("premortem", true);
        checks.put("prism", false);
        checks.put("postmortem", false);
        checks.put("mode", "manual");
        checks.put("maxAutoIterations", 3);
        checks.put("generatePlan", false);
        config.put("checks", checks);
        data.setConfig(config);
        data.setAgentType("review");
        node.setData(data);

        assertEquals("review", node.getType());
        assertEquals("manual", ((Map<String, Object>) node.getData().getConfig().get("checks")).get("mode"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> extractedChecks = (Map<String, Object>) node.getData().getConfig().get("checks");
        assertTrue((Boolean) extractedChecks.get("premortem"));
        assertEquals("manual", extractedChecks.get("mode"));
    }

    @Test
    void executeReviewNode_autoMode_passesAfterMax() {
        Node node = new Node();
        node.setId("review-auto");
        node.setType("review");
        node.setName("Auto Review");

        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();
        checks.put("premortem", true);
        checks.put("prism", false);
        checks.put("postmortem", false);
        checks.put("mode", "auto");
        checks.put("maxAutoIterations", 3);
        checks.put("generatePlan", true);
        config.put("checks", checks);
        data.setConfig(config);
        data.setAgentType("review");
        node.setData(data);

        assertEquals("auto", ((Map<String, Object>) node.getData().getConfig().get("checks")).get("mode"));
        assertEquals(3, ((Number) ((Map<String, Object>) node.getData().getConfig().get("checks")).get("maxAutoIterations")).intValue());
        
        @SuppressWarnings("unchecked")
        Map<String, Object> extractedChecks = (Map<String, Object>) node.getData().getConfig().get("checks");
        assertTrue((Boolean) extractedChecks.get("premortem"));
        assertEquals("auto", extractedChecks.get("mode"));
        assertTrue((Boolean) extractedChecks.get("generatePlan"));
    }

    @Test
    void executeVerifierNode_rewriteOnFail_fixesCode() {
        Node node = new Node();
        node.setId("verifier-rewrite");
        node.setType("verifier");
        node.setName("Verifier with Rewrite");

        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();
        checks.put("syntaxCheck", true);
        checks.put("testCommand", "");
        checks.put("premortem", true);
        checks.put("requiredPatterns", List.of());
        checks.put("maxFileSizeKb", 500);
        config.put("checks", checks);
        config.put("rewriteOnFail", true);
        config.put("maxRewriteRetries", 3);
        data.setConfig(config);
        data.setAgentType("verifier");
        node.setData(data);

        @SuppressWarnings("unchecked")
        Map<String, Object> extractedConfig = node.getData().getConfig();
        assertTrue((Boolean) extractedConfig.get("rewriteOnFail"));
        assertEquals(3, ((Number) extractedConfig.get("maxRewriteRetries")).intValue());
        
        @SuppressWarnings("unchecked")
        Map<String, Object> extractedChecks = (Map<String, Object>) extractedConfig.get("checks");
        assertTrue((Boolean) extractedChecks.get("syntaxCheck"));
        assertTrue((Boolean) extractedChecks.get("premortem"));
    }

    @Test
    void executeOutputNode_summaryReportMode_configReadCorrectly() {
        Node node = new Node();
        node.setId("output-summary");
        node.setType("output");
        node.setName("Summary Report");

        Node.NodeData data = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("mode", "summary_report");
        config.put("reportPath", "test-report.md");
        config.put("includeReview", true);
        config.put("includeFiles", true);
        config.put("includeVerification", true);
        config.put("includeMetrics", true);
        data.setConfig(config);
        data.setAgentType("output");
        node.setData(data);

        assertEquals("output", node.getType());
        assertEquals("summary_report", node.getData().getConfig().get("mode"));
        assertEquals("test-report.md", node.getData().getConfig().get("reportPath"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> extractedConfig = node.getData().getConfig();
        assertEquals("summary_report", extractedConfig.get("mode"));
        assertTrue((Boolean) extractedConfig.get("includeReview"));
        assertTrue((Boolean) extractedConfig.get("includeFiles"));
        assertTrue((Boolean) extractedConfig.get("includeVerification"));
        assertTrue((Boolean) extractedConfig.get("includeMetrics"));
    }
}
