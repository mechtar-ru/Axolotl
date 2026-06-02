package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.llm.MemPalaceClient;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class NodeRouterTest {

    @Mock NodeExecutor nodeExecutor;
    @Mock ExecutionUtilityService utilityService;
    @Mock LlmService llmService;
    @Mock ExecutionWebSocketHandler webSocketHandler;
    @Mock MemPalaceClient memPalaceClient;
    @Mock ToolExecutor toolExecutor;
    @Mock TransformService transformService;
    @Mock Neo4jSchemaRepository schemaRepository;
    @Mock PlanService planService;
    @Mock ProjectContextBuilder projectContextBuilder;
    @Mock ExecutionRepository executionRepository;
    @Mock ExecutionStateManager stateManager;
    @Mock ReasoningCapture reasoningCapture;

    NodeRouter nodeRouter;

    @BeforeEach
    void setUp() {
        nodeRouter = new NodeRouter(
                nodeExecutor, utilityService, llmService, webSocketHandler,
                memPalaceClient, toolExecutor, transformService, schemaRepository,
                planService, projectContextBuilder, executionRepository,
                stateManager, reasoningCapture);
    }

    // ─── getAutoRetryCount ───

    @Test
    void getAutoRetryCount_noConfig_returnsZero() {
        Node node = createNodeWithConfig(null);
        assertEquals(0, nodeRouter.getAutoRetryCount(node));
    }

    @Test
    void getAutoRetryCount_emptyConfig_returnsZero() {
        Node node = createNodeWithConfig(Map.of());
        assertEquals(0, nodeRouter.getAutoRetryCount(node));
    }

    @Test
    void getAutoRetryCount_noKey_returnsZero() {
        Node node = createNodeWithConfig(Map.of("otherKey", "value"));
        assertEquals(0, nodeRouter.getAutoRetryCount(node));
    }

    @Test
    void getAutoRetryCount_returnsConfiguredValue() {
        Node node = createNodeWithConfig(Map.of("autoRetryCount", 3));
        assertEquals(3, nodeRouter.getAutoRetryCount(node));
    }

    @Test
    void getAutoRetryCount_capsAtFive() {
        Node node = createNodeWithConfig(Map.of("autoRetryCount", 10));
        assertEquals(5, nodeRouter.getAutoRetryCount(node));
    }

    @Test
    void getAutoRetryCount_zeroIsZero() {
        Node node = createNodeWithConfig(Map.of("autoRetryCount", 0));
        assertEquals(0, nodeRouter.getAutoRetryCount(node));
    }

    @Test
    void getAutoRetryCount_noNodeData_returnsZero() {
        Node node = new Node();
        node.setId("test-node");
        assertEquals(0, nodeRouter.getAutoRetryCount(node));
    }

    // ─── isTransientError ───

    @Test
    void isTransientError_nullMessage_returnsFalse() {
        Exception e = new RuntimeException((String) null);
        assertFalse(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_429_transient() {
        Exception e = new RuntimeException("HTTP 429 Too Many Requests");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_502_transient() {
        Exception e = new RuntimeException("502 Bad Gateway");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_503_transient() {
        Exception e = new RuntimeException("503 Service Unavailable");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_rateLimit_transient() {
        Exception e = new RuntimeException("rate limit exceeded, retry in 30s");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_rateLimitWithUnderscore_transient() {
        Exception e = new RuntimeException("rate_limit_exceeded");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_tooManyRequests_transient() {
        Exception e = new RuntimeException("too many requests, please slow down");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_socketTimeout_transient() {
        Exception e = new SocketTimeoutException("Read timed out");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_timeoutMessage_transient() {
        Exception e = new RuntimeException("connect timed out");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_serviceUnavailable_transient() {
        Exception e = new RuntimeException("service unavailable, try again later");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_temporarilyUnavailable_transient() {
        Exception e = new RuntimeException("The server is temporarily unavailable");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_tryAgainLater_transient() {
        Exception e = new RuntimeException("Please try again later");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_internalServerError_transient() {
        Exception e = new RuntimeException("Internal server error occurred");
        assertTrue(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_nonTransient_returnsFalse() {
        Exception e = new RuntimeException("400 Bad Request - invalid input");
        assertFalse(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_nullPointer_returnsFalse() {
        Exception e = new NullPointerException("Cannot invoke because value is null");
        assertFalse(nodeRouter.isTransientError(e));
    }

    @Test
    void isTransientError_genericError_returnsFalse() {
        Exception e = new RuntimeException("Something went wrong");
        assertFalse(nodeRouter.isTransientError(e));
    }

    // ─── getTimeoutSeconds ───

    @Test
    void getTimeoutSeconds_noConfig_returnsDefault() {
        Node node = createNodeWithConfig(null);
        assertEquals(60, nodeRouter.getTimeoutSeconds(node));
    }

    @Test
    void getTimeoutSeconds_emptyConfig_returnsDefault() {
        Node node = createNodeWithConfig(Map.of());
        assertEquals(60, nodeRouter.getTimeoutSeconds(node));
    }

    @Test
    void getTimeoutSeconds_returnsConfiguredValue() {
        Node node = createNodeWithConfig(Map.of("timeoutSeconds", 120));
        assertEquals(120, nodeRouter.getTimeoutSeconds(node));
    }

    @Test
    void getTimeoutSeconds_minCapsAtOne() {
        Node node = createNodeWithConfig(Map.of("timeoutSeconds", 0));
        assertEquals(1, nodeRouter.getTimeoutSeconds(node));
    }

    @Test
    void getTimeoutSeconds_nodeDataFieldTakesPrecedence() {
        Node node = createNodeWithConfig(Map.of("timeoutSeconds", 30));
        node.getData().setTimeoutSeconds(120);
        assertEquals(120, nodeRouter.getTimeoutSeconds(node));
    }

    // ─── helpers ───

    private Node createNodeWithConfig(Map<String, Object> config) {
        Node node = new Node();
        node.setId("test-node");
        Node.NodeData data = new Node.NodeData();
        if (config != null) {
            data.setConfig(new HashMap<>(config));
        }
        node.setData(data);
        return node;
    }
}
