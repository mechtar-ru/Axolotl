package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Tool.ToolResult;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ToolHandlerService}.
 *
 * Tests cover all tool handlers in the service:
 *   file_read, file_write, directory_read, grep, bash,
 *   memory_write, memory_read, memory_search,
 *   graph_query, web_search, web_fetch.
 *
 * NOT covered here (handled by other classes):
 *   - build_app  → {@link BuildToolHandler}
 *   - tool_not_found  → {@link ToolExecutorImpl} handler map dispatch
 */
@ExtendWith(MockitoExtension.class)
class ToolHandlerServiceTest {

    @Mock
    private LlmService llmService;

    @Mock
    private ExecutionWebSocketHandler webSocketHandler;

    @Mock
    private ExecutionStateManager stateManager;

    @Mock
    private Driver neo4jDriver;

    @Mock
    private Neo4jSchemaRepository schemaRepository;

    @InjectMocks
    private ToolHandlerService service;

    // ──────────────────────────────────────────────
    //  File Read
    // ──────────────────────────────────────────────

    @Test
    void handleFileRead_happyPath() throws IOException {
        Path tempFile = Files.createTempFile("axolotl-test-", ".txt");
        try {
            Files.writeString(tempFile, "hello world");

            Map<String, Object> params = new HashMap<>();
            params.put("path", tempFile.toString());

            ToolResult result = service.handleFileRead(params, null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).isEqualTo("hello world");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void handleFileRead_altKey_filePath() throws IOException {
        Path tempFile = Files.createTempFile("axolotl-test-", ".txt");
        try {
            Files.writeString(tempFile, "alt key test");

            Map<String, Object> params = new HashMap<>();
            params.put("file_path", tempFile.toString());

            ToolResult result = service.handleFileRead(params, null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).isEqualTo("alt key test");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void handleFileRead_missingPath() {
        Map<String, Object> params = new HashMap<>();

        ToolResult result = service.handleFileRead(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing path");
    }

    @Test
    void handleFileRead_nonexistentFile() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "/tmp/axolotl-nonexistent-042f8e3.txt");

        ToolResult result = service.handleFileRead(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Failed to read file");
    }

    // ──────────────────────────────────────────────
    //  File Write
    // ──────────────────────────────────────────────

    @Test
    void handleFileWrite_happyPath() throws IOException {
        Path tempDir = Files.createTempDirectory("axolotl-test-");
        Path filePath = tempDir.resolve("test.txt");
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("path", filePath.toString());
            params.put("content", "hello world");

            ToolResult result = service.handleFileWrite(params, null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("File written");
            assertThat(Files.readString(filePath)).isEqualTo("hello world");
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void handleFileWrite_altKeys() throws IOException {
        Path tempDir = Files.createTempDirectory("axolotl-test-");
        Path filePath = tempDir.resolve("alt.txt");
        try {
            // Alt key "code" instead of "content"
            Map<String, Object> params = new HashMap<>();
            params.put("path", filePath.toString());
            params.put("code", "via code key");

            ToolResult result = service.handleFileWrite(params, null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(Files.readString(filePath)).isEqualTo("via code key");
        } finally {
            Files.deleteIfExists(filePath);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void handleFileWrite_missingParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "/tmp/test.txt");
        // missing content

        ToolResult result = service.handleFileWrite(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing path or content");
    }

    // ──────────────────────────────────────────────
    //  Directory Read
    // ──────────────────────────────────────────────

    @Test
    void handleDirectoryRead_happyPath() throws IOException {
        Path tempDir = Files.createTempDirectory("axolotl-test-");
        try {
            Files.createFile(tempDir.resolve("alpha.txt"));
            Files.createFile(tempDir.resolve("beta.txt"));
            // Ensure sorted order: alpha before beta
            Path subDir = tempDir.resolve("sub");
            Files.createDirectory(subDir);

            Map<String, Object> params = new HashMap<>();
            params.put("path", tempDir.toString());

            ToolResult result = service.handleDirectoryRead(params, null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("alpha.txt");
            assertThat(result.getOutput()).contains("beta.txt");
            assertThat(result.getOutput()).contains("sub");
        } finally {
            try (var files = Files.list(tempDir)) {
                files.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void handleDirectoryRead_defaultPath() throws IOException {
        // When path is null, defaults to "."
        Map<String, Object> params = new HashMap<>();

        ToolResult result = service.handleDirectoryRead(params, null);

        assertThat(result.isSuccess()).isTrue();
        // Should list current directory (project root or wherever Maven runs from)
        assertThat(result.getOutput()).isNotEmpty();
    }

    // ──────────────────────────────────────────────
    //  Memory Write
    // ──────────────────────────────────────────────

    @Test
    void handleMemoryWrite_happyPath() {
        Session session = mock(Session.class);
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(mock(Result.class));

        Map<String, Object> params = new HashMap<>();
        params.put("content", "test memory entry");

        ToolResult result = service.handleMemoryWrite(params, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Memory saved with id:");
    }

    @Test
    void handleMemoryWrite_withMetadata() {
        Session session = mock(Session.class);
        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(mock(Result.class));

        Map<String, Object> params = new HashMap<>();
        params.put("content", "memory with metadata");
        params.put("metadata", Map.of("source", "test", "priority", 1));

        ToolResult result = service.handleMemoryWrite(params, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Memory saved with id:");
    }

    @Test
    void handleMemoryWrite_missingContent() {
        Map<String, Object> params = new HashMap<>();

        ToolResult result = service.handleMemoryWrite(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing content parameter");
    }

    // ──────────────────────────────────────────────
    //  Memory Read
    // ──────────────────────────────────────────────

    @Test
    void handleMemoryRead_happyPath() {
        Session session = mock(Session.class);
        Result neoResult = mock(Result.class);
        Record record = mock(Record.class);

        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(neoResult);
        when(neoResult.hasNext()).thenReturn(true, false);
        when(neoResult.next()).thenReturn(record);

        Value createdAt = mock(Value.class);
        when(createdAt.toString()).thenReturn("2024-01-01T12:00:00");
        Value idValue = mock(Value.class);
        when(idValue.asString()).thenReturn("mem-001");
        Value contentValue = mock(Value.class);
        when(contentValue.asString()).thenReturn("existing memory");

        when(record.get("m.createdAt")).thenReturn(createdAt);
        when(record.get("m.id")).thenReturn(idValue);
        when(record.get("m.content")).thenReturn(contentValue);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "memory");

        ToolResult result = service.handleMemoryRead(params, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("existing memory");
        assertThat(result.getOutput()).contains("Memories (1)");
    }

    // ──────────────────────────────────────────────
    //  Memory Search
    // ──────────────────────────────────────────────

    @Test
    void handleMemorySearch_happyPath() {
        Session session = mock(Session.class);
        Result neoResult = mock(Result.class);
        Record record = mock(Record.class);

        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(neoResult);
        when(neoResult.hasNext()).thenReturn(true, false);
        when(neoResult.next()).thenReturn(record);

        Value createdAt = mock(Value.class);
        when(createdAt.toString()).thenReturn("2024-06-15T08:30:00");
        Value idValue = mock(Value.class);
        when(idValue.asString()).thenReturn("mem-456");
        Value contentValue = mock(Value.class);
        when(contentValue.asString()).thenReturn("found search result");

        when(record.get("m.createdAt")).thenReturn(createdAt);
        when(record.get("m.id")).thenReturn(idValue);
        when(record.get("m.content")).thenReturn(contentValue);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "search term");

        ToolResult result = service.handleMemorySearch(params, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("found search result");
        assertThat(result.getOutput()).contains("Memory search results (1)");
    }

    @Test
    void handleMemorySearch_noResults() {
        Session session = mock(Session.class);
        Result neoResult = mock(Result.class);

        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(anyString(), anyMap())).thenReturn(neoResult);
        when(neoResult.hasNext()).thenReturn(false);

        Map<String, Object> params = new HashMap<>();
        params.put("query", "nonexistent");

        ToolResult result = service.handleMemorySearch(params, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("No memories found for: nonexistent");
    }

    @Test
    void handleMemorySearch_missingQuery() {
        Map<String, Object> params = new HashMap<>();

        ToolResult result = service.handleMemorySearch(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing required parameter: query");
    }

    // ──────────────────────────────────────────────
    //  Bash
    // ──────────────────────────────────────────────

    @Test
    void handleBash_allowedCommand() {
        Map<String, Object> params = new HashMap<>();
        params.put("command", "echo hello");
        params.put("timeout", 10);

        ToolResult result = service.handleBash(params, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("hello");
    }

    @Test
    void handleBash_blockedByPermission() {
        ToolPermission permission = mock(ToolPermission.class);
        when(permission.allowsCommand(anyString())).thenReturn(false);

        Map<String, Object> params = new HashMap<>();
        params.put("command", "echo hello");

        ToolResult result = service.handleBash(params, permission);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("blocked by permissions");
    }

    @Test
    void handleBash_commandNotAllowed() {
        Map<String, Object> params = new HashMap<>();
        params.put("command", "some_unknown_cmd_42");

        ToolResult result = service.handleBash(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not allowed");
    }

    @Test
    void handleBash_substitutionBlocked() {
        Map<String, Object> params = new HashMap<>();
        params.put("command", "echo $(whoami)");

        ToolResult result = service.handleBash(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("substitution");
    }

    @Test
    void handleBash_backtickBlocked() {
        Map<String, Object> params = new HashMap<>();
        params.put("command", "echo `whoami`");

        ToolResult result = service.handleBash(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("substitution");
    }

    @Test
    void handleBash_chainingBlocked() {
        Map<String, Object> params = new HashMap<>();
        // Avoid "rm" in the command — the rm word-boundary check fires before chaining check
        params.put("command", "echo hello && echo world");

        ToolResult result = service.handleBash(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("chaining");
    }

    @Test
    void handleBash_missingCommand() {
        Map<String, Object> params = new HashMap<>();

        ToolResult result = service.handleBash(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing command");
    }

    // ──────────────────────────────────────────────
    //  Grep
    // ──────────────────────────────────────────────

    @Test
    void handleGrep_invalidPattern_shellMetacharacters() {
        Map<String, Object> params = new HashMap<>();
        params.put("pattern", "$(rm -rf /)");
        params.put("path", "/tmp");

        ToolResult result = service.handleGrep(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("shell metacharacters");
    }

    @Test
    void handleGrep_invalidPath() {
        Map<String, Object> params = new HashMap<>();
        params.put("pattern", "test");
        params.put("path", "/tmp/$(pwd)");

        ToolResult result = service.handleGrep(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("invalid characters");
    }

    @Test
    void handleGrep_missingPattern() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "/tmp");

        ToolResult result = service.handleGrep(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing required");
    }

    // ──────────────────────────────────────────────
    //  Graph Query
    // ──────────────────────────────────────────────

    @Test
    void handleGraphQuery_readQuery() {
        Session session = mock(Session.class);
        Result neoResult = mock(Result.class);
        Record record = mock(Record.class);
        TransactionContext txContext = mock(TransactionContext.class);

        when(neo4jDriver.session()).thenReturn(session);
        when(session.executeRead(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<List<Record>> callback = invocation.getArgument(0);
            return callback.execute(txContext);
        });
        when(txContext.run(anyString())).thenReturn(neoResult);
        when(neoResult.list()).thenReturn(List.of(record));
        when(record.keys()).thenReturn(List.of("name"));
        when(record.get("name")).thenReturn(Values.value("testNode"));

        Map<String, Object> params = new HashMap<>();
        params.put("query", "MATCH (n:Test) RETURN n.name");

        ToolResult result = service.handleGraphQuery(params, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("testNode");
        assertThat(result.getOutput()).contains("Results (1)");
    }

    @Test
    void handleGraphQuery_readQuery_noResults() {
        Session session = mock(Session.class);
        Result neoResult = mock(Result.class);
        TransactionContext txContext = mock(TransactionContext.class);

        when(neo4jDriver.session()).thenReturn(session);
        when(session.executeRead(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<List<Record>> callback = invocation.getArgument(0);
            return callback.execute(txContext);
        });
        when(txContext.run(anyString())).thenReturn(neoResult);
        when(neoResult.list()).thenReturn(List.of());

        Map<String, Object> params = new HashMap<>();
        params.put("query", "MATCH (n:Test) RETURN n.name LIMIT 5");

        ToolResult result = service.handleGraphQuery(params, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("No results found");
    }

    @Test
    void handleGraphQuery_writeQuery_createBlocked() {
        // Query must pass the ALLOWED_CYPHER check (starts with MATCH/RETURN/etc.)
        // before the write-ops check fires
        Map<String, Object> params = new HashMap<>();
        params.put("query", "MATCH (n:Test) CREATE (m:Other {name: 'foo'}) RETURN n");

        ToolResult result = service.handleGraphQuery(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("write operations not allowed");
    }

    @Test
    void handleGraphQuery_writeQuery_deleteBlocked() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "MATCH (n:Test) DETACH DELETE n");

        ToolResult result = service.handleGraphQuery(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("write operations not allowed");
    }

    @Test
    void handleGraphQuery_writeQuery_setBlocked() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "MATCH (n:Test) SET n.name = 'bar'");

        ToolResult result = service.handleGraphQuery(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("write operations not allowed");
    }

    @Test
    void handleGraphQuery_writeQuery_mergeBlocked() {
        // Query starts with MATCH to pass ALLOWED_CYPHER, then MERGE triggers write-ops check
        Map<String, Object> params = new HashMap<>();
        params.put("query", "MATCH (n:Test {name: 'baz'}) MERGE (n)");

        ToolResult result = service.handleGraphQuery(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("write operations not allowed");
    }

    @Test
    void handleGraphQuery_missingQuery() {
        Map<String, Object> params = new HashMap<>();

        ToolResult result = service.handleGraphQuery(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing");
    }

    @Test
    void handleGraphQuery_nonMatchQuery() {
        // Queries that don't start with MATCH/RETURN/WHERE etc. are also blocked
        Map<String, Object> params = new HashMap<>();
        params.put("query", "CALL db.labels()");

        ToolResult result = service.handleGraphQuery(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("read-only");
    }

    // ──────────────────────────────────────────────
    //  Web Search  (parameter validation)
    // ──────────────────────────────────────────────
    //  Full HTTP mocking would require HttpClient
    //  construction refactoring; parameter validation
    //  tests verify the handler's own logic.

    @Test
    void handleWebSearch_missingQuery() {
        Map<String, Object> params = new HashMap<>();

        ToolResult result = service.handleWebSearch(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing query");
    }

    @Test
    void handleWebSearch_blankQuery() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "   ");

        ToolResult result = service.handleWebSearch(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing query");
    }

    // ──────────────────────────────────────────────
    //  Web Fetch  (parameter validation)
    // ──────────────────────────────────────────────

    @Test
    void handleWebFetch_missingUrl() {
        Map<String, Object> params = new HashMap<>();

        ToolResult result = service.handleWebFetch(params, null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("Missing url");
    }
}
