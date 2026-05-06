package com.agent.orchestrator.graph.mcp;

import com.agent.orchestrator.graph.api.GraphController;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GraphMcpTools {

    private final GraphController graphController;

    public GraphMcpTools(GraphController graphController) {
        this.graphController = graphController;
    }

    public Map<String, Object> loadCodebase(Map<String, Object> args) {
        String path = (String) args.get("path");
        var response = graphController.loadCodebase(path);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", response.getBody().toString()
                ))
        );
    }

    public Map<String, Object> getClassByHash(Map<String, Object> args) {
        String hashOrName = (String) args.get("hash");
        var response = graphController.getClass(hashOrName);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", response.getBody() != null ? response.getBody().toString() : "Not found"
                ))
        );
    }

    public Map<String, Object> computeHash(Map<String, Object> args) {
        String packageName = (String) args.get("packageName");
        String className = (String) args.get("className");
        String content = (String) args.get("content");

        Map<String, String> request = Map.of(
                "packageName", packageName != null ? packageName : "",
                "className", className != null ? className : "",
                "content", content != null ? content : ""
        );

        var response = graphController.computeClassHash(request);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", response.getBody().toString()
                ))
        );
    }

    public Map<String, Object> searchPattern(Map<String, Object> args) {
        String pattern = (String) args.get("pattern");
        String type = (String) args.getOrDefault("type", "method");

        Map<String, Object> request = Map.of(
                "pattern", pattern != null ? pattern : "",
                "type", type != null ? type : "method"
        );

        var response = graphController.searchAst(request);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", response.getBody().toString()
                ))
        );
    }

    public Map<String, Object> getContext(Map<String, Object> args) {
        String query = (String) args.get("query");
        int budget = args.containsKey("budget") ? (int) args.get("budget") : 2000;
        List<String> recent = args.containsKey("recent") ?
                (List<String>) args.get("recent") : List.of();

        Map<String, Object> request = Map.of(
                "query", query != null ? query : "",
                "tokenBudget", budget,
                "recentHashes", recent
        );

        var response = graphController.curateContext(request);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", response.getBody().toString()
                ))
        );
    }

    public Map<String, Object> planEdit(Map<String, Object> args) {
        String operation = (String) args.get("operation");
        Map<String, Object> request = Map.of(
                "operation", operation != null ? operation : "default"
        );

        var response = graphController.planBatch(request);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", response.getBody().toString()
                ))
        );
    }

    public Map<String, Object> getImpact(Map<String, Object> args) {
        String hash = (String) args.get("hash");
        var response = graphController.analyzeImpact(hash);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", response.getBody().toString()
                ))
        );
    }

    public static List<Map<String, Object>> getToolDefinitions() {
        return List.of(
                Map.of(
                        "name", "graph_load_codebase",
                        "description", "Load and parse Java codebase into Neo4j graph",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "Path to Java source directory")
                                )
                        )
                ),
                Map.of(
                        "name", "graph_get_class",
                        "description", "Get class by hash or qualified name. Use hash for stable references.",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "hash", Map.of("type", "string", "description", "16-char hash or qualified name")
                                )
                        )
                ),
                Map.of(
                        "name", "graph_compute_hash",
                        "description", "Compute stable hash for a class (for hash-anchored edits)",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "packageName", Map.of("type", "string"),
                                        "className", Map.of("type", "string"),
                                        "content", Map.of("type", "string")
                                )
                        )
                ),
                Map.of(
                        "name", "graph_search_pattern",
                        "description", "Search code by AST patterns (body contains, imports, etc)",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "pattern", Map.of("type", "string"),
                                        "type", Map.of("type", "string", "enum", List.of("method", "import", "returnType"))
                                )
                        )
                ),
                Map.of(
                        "name", "graph_get_context",
                        "description", "Get curated context for LLM within token budget (hybrid relevance + centrality)",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string"),
                                        "budget", Map.of("type", "integer", "default", 2000),
                                        "recent", Map.of("type", "array", "items", Map.of("type", "string"))
                                )
                        )
                ),
                Map.of(
                        "name", "graph_plan_edit",
                        "description", "Plan batch edits based on dependency graph",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "operation", Map.of("type", "string", "enum", List.of("rename", "refactor", "default"))
                                )
                        )
                ),
                Map.of(
                        "name", "graph_impact",
                        "description", "Analyze impact of changes (classes/methods that will be affected)",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "hash", Map.of("type", "string")
                                )
                        )
                )
        );
    }
}