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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NodeRouterTest {

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
    @Mock AgentNodeStrategy agentStrategy;
    @Mock SchemaBuilderNodeStrategy schemaBuilderStrategy;
    @Mock VerifierNodeStrategy verifierStrategy;
    @Mock ReviewNodeStrategy reviewStrategy;
    @Mock DraftNodeStrategy draftStrategy;
    @Mock NodeOutputValidator outputValidator;
    @Mock MagicContextIndexer mcIndexer;

    NodeRouter nodeRouter;

    @BeforeEach
    void setUp() {
        nodeRouter = new NodeRouter(
                utilityService, llmService, webSocketHandler,
                memPalaceClient, toolExecutor, transformService, schemaRepository,
                planService, projectContextBuilder, executionRepository,
                stateManager, reasoningCapture,
                List.of(agentStrategy, schemaBuilderStrategy, verifierStrategy, reviewStrategy, draftStrategy),
                agentStrategy, outputValidator, mcIndexer);
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
    void getAutoRetryCount_negativeValue_returnsZero() {
        Node node = createNodeWithConfig(Map.of("autoRetryCount", -1));
        assertEquals(0, nodeRouter.getAutoRetryCount(node));
    }

    // ─── getTimeoutSeconds ───

    @Test
    void getTimeoutSeconds_emptyConfig_returnsDefault() {
        Node node = createNodeWithConfig(Map.of());
        assertEquals(300, nodeRouter.getTimeoutSeconds(node));
    }

    @Test
    void getTimeoutSeconds_noConfig_returnsDefault() {
        Node node = createNodeWithConfig(null);
        assertEquals(300, nodeRouter.getTimeoutSeconds(node));
    }

    @Test
    void getTimeoutSeconds_fromDataTimeoutSeconds() {
        Node node = new Node();
        node.setData(new Node.NodeData());
        node.getData().setTimeoutSeconds(120);
        assertEquals(120, nodeRouter.getTimeoutSeconds(node));
    }

    @Test
    void getTimeoutSeconds_fromConfigTimeoutSeconds() {
        Node node = createNodeWithConfig(Map.of("timeoutSeconds", 90));
        assertEquals(90, nodeRouter.getTimeoutSeconds(node));
    }

    @Test
    void getTimeoutSeconds_dataPriorityOverConfig() {
        Node node = new Node();
        node.setData(new Node.NodeData());
        node.getData().setTimeoutSeconds(60);
        node.getData().setConfig(Map.of("timeoutSeconds", 90));
        assertEquals(60, nodeRouter.getTimeoutSeconds(node));
    }

    @Test
    void getTimeoutSeconds_capsAtStageTimeout() {
        Node node = createNodeWithConfig(Map.of("timeoutSeconds", 2000));
        assertEquals(1200, nodeRouter.getTimeoutSeconds(node));
    }

    // ─── isTransientError ───

    @Test
    void isTransientError_null_false() {
        assertFalse(nodeRouter.isTransientError(null));
    }

    @Test
    void isTransientError_timeoutMessage_transient() {
        assertTrue(nodeRouter.isTransientError(new SocketTimeoutException("timeout")));
    }

    @Test
    void isTransientError_temporarilyUnavailable_transient() {
        assertTrue(nodeRouter.isTransientError(new RuntimeException("temporarily unavailable")));
    }

    @Test
    void isTransientError_rateLimitWithUnderscore_transient() {
        assertTrue(nodeRouter.isTransientError(new RuntimeException("rate_limit exceeded")));
    }

    @Test
    void isTransientError_rateLimitWithSpace_transient() {
        assertTrue(nodeRouter.isTransientError(new RuntimeException("rate limit exceeded")));
    }

    @Test
    void isTransientError_connectionReset_transient() {
        assertTrue(nodeRouter.isTransientError(new RuntimeException("connection reset")));
    }

    @Test
    void isTransientError_genericRuntimeException_false() {
        assertFalse(nodeRouter.isTransientError(new RuntimeException("some other error")));
    }

    @Test
    void isTransientError_wrappedTransient_transient() {
        RuntimeException wrapper = new RuntimeException("wrapped", new SocketTimeoutException("timeout"));
        assertTrue(nodeRouter.isTransientError(wrapper));
    }

    // ─── Helpers ───

    private Node createNodeWithConfig(Map<String, Object> config) {
        Node node = new Node();
        if (config != null) {
            node.setData(new Node.NodeData());
            node.getData().setConfig(config);
        }
        return node;
    }
}