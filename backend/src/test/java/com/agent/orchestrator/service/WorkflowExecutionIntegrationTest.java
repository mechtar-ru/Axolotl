package com.agent.orchestrator.service;

import com.agent.orchestrator.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the full workflow execution pipeline.
 * Creates a 3-node schema (source → agent → output), executes it,
 * and verifies completion in a single test method.
 *
 * Requires: Neo4j running at localhost:7687
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class WorkflowExecutionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private String apiBase;

    @BeforeEach
    void setUp() {
        apiBase = "http://localhost:" + port + "/api";
    }

    @Test
    @DisplayName("Full workflow lifecycle: create → execute → verify")
    void testFullWorkflowLifecycle() throws Exception {
        // ══════ STEP 1: Create schema ══════

        WorkflowSchema schema = new WorkflowSchema();
        schema.setName("IT-3-Node Workflow");
        schema.setDescription("Integration test: source → agent → output");
        schema.setVersion("1.0");
        schema.setDefaultModel("local");

        Node source = new Node();
        source.setId("src-1");
        source.setType("source");
        source.setName("Test Source");
        Node.Position pos1 = new Node.Position();
        pos1.setX(100); pos1.setY(200);
        source.setPosition(pos1);
        Node.NodeData srcData = new Node.NodeData();
        srcData.setSourceData("Hello from integration test");
        source.setData(srcData);

        Node agent = new Node();
        agent.setId("agt-1");
        agent.setType("agent");
        agent.setName("Test Agent");
        Node.Position pos2 = new Node.Position();
        pos2.setX(400); pos2.setY(200);
        agent.setPosition(pos2);
        Node.NodeData agtData = new Node.NodeData();
        agtData.setUserPrompt("Echo: {input}");
        agtData.setModel("local");
        agent.setData(agtData);

        Node output = new Node();
        output.setId("out-1");
        output.setType("output");
        output.setName("Test Output");
        Node.Position pos3 = new Node.Position();
        pos3.setX(700); pos3.setY(200);
        output.setPosition(pos3);
        Node.NodeData outData = new Node.NodeData();
        Map<String, Object> config = new HashMap<>();
        config.put("outputType", "log");
        outData.setConfig(config);
        output.setData(outData);

        schema.setNodes(List.of(source, agent, output));

        Edge e1 = new Edge();
        e1.setId("edge-1");
        e1.setSource("src-1");
        e1.setTarget("agt-1");
        e1.setType("data");

        Edge e2 = new Edge();
        e2.setId("edge-2");
        e2.setSource("agt-1");
        e2.setTarget("out-1");
        e2.setType("data");

        schema.setEdges(List.of(e1, e2));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<WorkflowSchema> request = new HttpEntity<>(schema, headers);

        ResponseEntity<WorkflowSchema> createResponse = restTemplate.postForEntity(
                apiBase + "/schemas", request, WorkflowSchema.class);

        assertThat(createResponse.getStatusCodeValue()).isEqualTo(200);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().getId()).isNotNull();

        String schemaId = createResponse.getBody().getId();
        System.out.println("Created schema: " + schemaId);

        // ══════ STEP 2: Execute ══════

        ResponseEntity<Map> execResponse = restTemplate.postForEntity(
                apiBase + "/schemas/" + schemaId + "/execute",
                null, Map.class);

        assertThat(execResponse.getStatusCodeValue()).isEqualTo(200);
        assertThat(execResponse.getBody()).isNotNull();
        assertThat(execResponse.getBody().get("status")).isEqualTo("started");

        // ══════ STEP 3: Poll for completion ══════

        WorkflowSchema completed = null;
        long deadline = System.currentTimeMillis() + 30_000;

        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<WorkflowSchema> getResponse = restTemplate.getForEntity(
                    apiBase + "/schemas/" + schemaId, WorkflowSchema.class);

            if (getResponse.getStatusCodeValue() == 200 && getResponse.getBody() != null) {
                completed = getResponse.getBody();
                boolean allDone = completed.getNodes().stream()
                        .allMatch(n -> n.getStatus() == Node.NodeStatus.COMPLETED
                                || n.getStatus() == Node.NodeStatus.FAILED);
                if (allDone) break;
            }
            Thread.sleep(1000);
        }

        assertThat(completed).as("Execution should complete within 30s timeout").isNotNull();
        for (Node n : completed.getNodes()) {
            System.out.printf("Node %s (%s): status=%s%n",
                    n.getId(), n.getType(), n.getStatus());
            assertThat(n.getStatus())
                    .as("Node %s should complete", n.getId())
                    .isEqualTo(Node.NodeStatus.COMPLETED);
        }

        // ══════ STEP 4: Verify history ══════

        ResponseEntity<ExecutionRecord[]> historyResponse = restTemplate.getForEntity(
                apiBase + "/schemas/" + schemaId + "/history",
                ExecutionRecord[].class);

        assertThat(historyResponse.getStatusCodeValue()).isEqualTo(200);
        assertThat(historyResponse.getBody()).isNotNull();
        assertThat(historyResponse.getBody().length).isGreaterThanOrEqualTo(1);
        assertThat(historyResponse.getBody()[0].getStatus()).isIn("completed", "cancelled");
        System.out.printf("Execution record: status=%s, nodes=%d%n",
                historyResponse.getBody()[0].getStatus(),
                historyResponse.getBody()[0].getTotalNodes());
    }
}
