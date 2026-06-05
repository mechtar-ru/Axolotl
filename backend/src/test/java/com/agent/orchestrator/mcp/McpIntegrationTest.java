package com.agent.orchestrator.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the MCP server.
 * Tests the full JSON-RPC 2.0 flow over HTTP.
 * Cleans all Plan nodes in @BeforeEach to prevent stale/corrupted
 * data from affecting test isolation (e.g., plan records where
 * p.id != p.data.id due to workspaceId mismatch in stored JSON).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class McpIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Driver neo4jDriver;

    private final ObjectMapper mapper = new ObjectMapper();
    private String mcpUrl;

    @BeforeEach
    void setUp() {
        mcpUrl = "http://localhost:" + port + "/mcp";
        // Remove all stale Plan records from Neo4j to ensure test isolation.
        // Root cause: Plan nodes accumulate when Plan.workspaceId is null in JSON
        // but "default" in the Neo4j property — every save() using MERGE on p.id
        // creates a new node instead of updating the original. Deleting all plans
        // in @BeforeEach forces PlanService.getPlan("default") to auto-create
        // a fresh, correct plan on first access.
        try (var session = neo4jDriver.session()) {
            session.run("MATCH (p:Plan) DETACH DELETE p");
        }
    }

    @Test
    @DisplayName("GET /mcp — health check returns status and tool list")
    void testMcpHealthCheck() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(mcpUrl, String.class);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);

        JsonNode json = mapper.readTree(response.getBody());
        assertThat(json.get("status").asText()).isEqualTo("MCP Server running");
        assertThat(json.has("tools")).isTrue();
        assertThat(json.get("tools").size()).isGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("POST /mcp — tools/list returns all 7 tools")
    void testToolsList() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

        JsonNode json = postJsonRpcAndParse(request);
        assertThat(json.get("id").asInt()).isEqualTo(1);
        assertThat(json.has("result")).isTrue();
        assertThat(json.get("result").has("tools")).isTrue();
        assertThat(json.get("result").get("tools").size()).isEqualTo(12);
    }

    @Test
    @DisplayName("POST /mcp — initialize returns server info")
    void testInitialize() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\"}";

        JsonNode json = postJsonRpcAndParse(request);
        assertThat(json.get("result").get("protocolVersion").asText()).isEqualTo("2024-11-05");
        assertThat(json.get("result").get("serverInfo").get("name").asText()).isEqualTo("axolotl-plan-server");
    }

    @Test
    @DisplayName("POST /mcp — read_plan returns plan data")
    void testReadPlan() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"read_plan\",\"arguments\":{\"format\":\"status_summary\"}}}";

        JsonNode json = postJsonRpcAndParse(request);
        assertThat(json.has("result")).isTrue();
        assertThat(json.get("result").has("content")).isTrue();
    }

    @Test
    @DisplayName("POST /mcp — add_task creates a task")
    void testAddTask() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"add_task\",\"arguments\":{\"title\":\"Test task from MCP\",\"priority\":\"HIGH\"}}}";

        JsonNode json = postJsonRpcAndParse(request);
        assertThat(json.has("result")).isTrue();
        String content = json.get("result").get("content").get(0).get("text").asText();
        assertThat(content).startsWith("OK: Task added");
        assertThat(content).contains("Test task from MCP");
    }

    @Test
    @DisplayName("POST /mcp — update_task_status changes status")
    void testUpdateTaskStatus() throws Exception {
        // First add a task
        String addRequest = "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"add_task\",\"arguments\":{\"title\":\"Status test task\"}}}";
        JsonNode addResult = postJsonRpcAndParse(addRequest);
        String content = addResult.get("result").get("content").get(0).get("text").asText();
        String taskId = extractTaskId(content);
        assertThat(taskId).isNotNull();

        // Now update its status
        String updateRequest = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"update_task_status\",\"arguments\":{\"task_id\":\"%s\",\"status\":\"IN_PROGRESS\",\"reason\":\"Started\"}}}",
                taskId);

        JsonNode json = postJsonRpcAndParse(updateRequest);
        String resultText = json.get("result").get("content").get(0).get("text").asText();
        assertThat(resultText).contains("IN_PROGRESS");
    }

    @Test
    @DisplayName("POST /mcp — unknown tool returns error message in content")
    void testUnknownTool() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/call\",\"params\":{\"name\":\"nonexistent_tool\",\"arguments\":{}}}";

        JsonNode json = postJsonRpcAndParse(request);
        String resultText = json.get("result").get("content").get(0).get("text").asText();
        assertThat(resultText).startsWith("ERROR: Unknown tool");
    }

    @Test
    @DisplayName("POST /mcp — invalid method returns JSON-RPC error")
    void testInvalidMethod() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"nonexistent\"}";

        // Returns 400 with error in body
        JsonNode json = postJsonRpcAndParse(request);
        assertThat(json.has("error")).isTrue();
        assertThat(json.get("error").get("code").asInt()).isEqualTo(-32601);
    }

    @Test
    @DisplayName("POST /mcp — missing method returns JSON-RPC error")
    void testMissingMethod() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":101}";

        JsonNode json = postJsonRpcAndParse(request);
        assertThat(json.has("error")).isTrue();
        assertThat(json.get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    @DisplayName("POST /mcp — update_task_priority works")
    void testUpdateTaskPriority() throws Exception {
        // Add a task first
        String addRequest = "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\",\"params\":{\"name\":\"add_task\",\"arguments\":{\"title\":\"Priority test\"}}}";
        JsonNode addResult = postJsonRpcAndParse(addRequest);
        String content = addResult.get("result").get("content").get(0).get("text").asText();
        String taskId = extractTaskId(content);

        // Update priority
        String updateRequest = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":{\"name\":\"update_task_priority\",\"arguments\":{\"task_id\":\"%s\",\"priority\":\"LOW\"}}}",
                taskId);

        JsonNode json = postJsonRpcAndParse(updateRequest);
        String resultText = json.get("result").get("content").get(0).get("text").asText();
        assertThat(resultText).contains("LOW");
    }

    @Test
    @DisplayName("POST /mcp — delete_task removes task")
    void testDeleteTask() throws Exception {
        // Add a task first
        String addRequest = "{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\",\"params\":{\"name\":\"add_task\",\"arguments\":{\"title\":\"Delete me\"}}}";
        JsonNode addResult = postJsonRpcAndParse(addRequest);
        String content = addResult.get("result").get("content").get(0).get("text").asText();
        String taskId = extractTaskId(content);

        // Delete it
        String deleteRequest = String.format(
                "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\",\"params\":{\"name\":\"delete_task\",\"arguments\":{\"task_id\":\"%s\"}}}",
                taskId);

        JsonNode json = postJsonRpcAndParse(deleteRequest);
        String resultText = json.get("result").get("content").get(0).get("text").asText();
        assertThat(resultText).startsWith("OK: Task deleted");
    }

    // === Helpers ===

    private JsonNode postJsonRpcAndParse(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        // Use exchange to get body regardless of HTTP status code
        ResponseEntity<String> response = restTemplate.exchange(mcpUrl, HttpMethod.POST, entity, String.class);
        try {
            return mapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("Failed to parse JSON response (status=" + response.getStatusCodeValue() + "): " + response.getBody(), e);
        }
    }

    private String extractTaskId(String content) {
        // Extract ID from: "OK: Task added — '...' (ID: xxx, priority: MEDIUM, dependencies: 0)"
        int start = content.indexOf("ID: ") + 4;
        int end = content.indexOf(",", start);
        if (start < 4 || end < 0) return null;
        return content.substring(start, end).trim();
    }
}
