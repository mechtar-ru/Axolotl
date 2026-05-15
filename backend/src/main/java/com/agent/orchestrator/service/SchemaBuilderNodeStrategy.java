package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Plan;
import com.agent.orchestrator.model.Priority;
import com.agent.orchestrator.model.ToolPermission;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Strategy for executing schema-builder nodes.
 * Uses ExecutionUtilityService for shared helper methods.
 */
@Component
public class SchemaBuilderNodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(SchemaBuilderNodeStrategy.class);

    private final ExecutionUtilityService utilityService;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final Neo4jSchemaRepository schemaRepository;
    private final PlanService planService;

    private static final String SCHEMA_BUILDER_SYSTEM_PROMPT = """
            You are a workflow architect. Given an analysis/result text, design an Axolotl workflow schema.

            CRITICAL PATH RULES:
            - If input mentions "backend-next" or "frontend-next" or "-next", you MUST use these exact paths:
              * /backend-next/ NOT /backend/
              * /Users/evgenijtihomirov/git/Axolotl/Axolotl/backend-next/ NOT /Users/evgenijtihomirov/git/Axolotl/Axolotl/backend/
              * Use FULL paths starting with /Users/evgenijtihomirov/git/Axolotl/Axolotl/...

            For generated schemas, ALWAYS set model to "minimax-max" (powerful) or "minimax-m2.5-free" (simple).
            Avoid using openai models unless explicitly requested.

            Respond ONLY with valid JSON, no markdown fences:
            {
              "name": "Schema name",
              "description": "What this workflow does",
              "nodes": [
                {
                  "id": "n1",
                  "type": "source|agent|output|condition|loop|memory|guardrail|human|fallback|schemabuilder",
                  "name": "Node name",
                  "position": {"x": 100, "y": 200},
                  "data": {
                    "userPrompt": "...",
                    "systemPrompt": "...",
                    "model": "minimax-max",
                    "agentType": "coder|assistant|researcher|reviewer",
                    "enabledTools": ["file_read", "file_write", "directory_read", "grep", "bash"],
                    "maxToolCalls": 50,
                    "toolPermissions": [
                      {"toolId": "file_read", "allowedPaths": ["/full/path/**"], "enabled": true}
                    ]
                  }
                }
              ],
              "edges": [
                {"source": "n1", "target": "n2"}
              ],
              "planExplanation": "Markdown text explaining the plan"
            }
            Rules:
            - Use agent nodes with detailed userPrompt and systemPrompt
            - For tool-enabled agents, ALWAYS specify enabledTools array and toolPermissions
            - Tools: file_read, file_write, directory_read, grep, git, bash, memory_read, memory_write, memory_search, web_search, web_fetch
            - Typical flow for implementation: source -> agent (read files) -> agent (apply changes) -> agent (test)
            - Position nodes with reasonable spacing (x increments of 300)
            - Each node needs a unique id (n1, n2, n3...)
            """;

    public SchemaBuilderNodeStrategy(ExecutionUtilityService utilityService,
                                     LlmService llmService,
                                     ExecutionWebSocketHandler webSocketHandler,
                                     Neo4jSchemaRepository schemaRepository,
                                     PlanService planService) {
        this.utilityService = utilityService;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.schemaRepository = schemaRepository;
        this.planService = planService;
    }

    public String executeSchemaBuilderNode(Node node, String schemaId, String resolvedModel) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Building schema from agent result");
            webSocketHandler.sendLog(schemaId, "info", "SchemaBuilder: generating workflow", node.getId());
        }

        var predResults = utilityService.collectPredecessorResults(schemaRepository.findById(schemaId), node.getId());
        String input = predResults.values().stream().findFirst().map(Object::toString).orElse("");
        if (input.isBlank()) {
            return "Error: no predecessor result to build schema from";
        }

        boolean generateMd = node.getData() != null && node.getData().getConfig() != null
                && Boolean.TRUE.equals(node.getData().getConfig().get("generateMd"));

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 40, "Sending to LLM");
        }

        String model = resolvedModel;
        if (model == null) {
            model = utilityService.resolveModel(node.getData() != null ? node.getData().getModel() : null,
                    null, null, null);
        }
        String llmResponse = llmService.chat(model, SCHEMA_BUILDER_SYSTEM_PROMPT, input, null);

        if (llmResponse == null || llmResponse.isBlank()
                || llmResponse.startsWith("Error:") || llmResponse.startsWith("Ollama")) {
            return "Error: LLM call failed — " + (llmResponse != null ? llmResponse : "empty response");
        }

        String jsonStr = llmResponse.trim();
        if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.replaceFirst("^```\\w*\\n?", "").replaceFirst("\\n?```$", "");
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 60, "Parsing schema");
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(jsonStr);
        } catch (Exception e) {
            return "Error: failed to parse LLM response as JSON: " + e.getMessage();
        }

        WorkflowSchema newSchema = new WorkflowSchema();
        newSchema.setName(root.has("name") ? root.get("name").asText() : "Generated Schema");
        newSchema.setDescription(root.has("description") ? root.get("description").asText() : "");
        newSchema.setVersion("1.0");

        List<Node> nodes = new ArrayList<>();
        if (root.has("nodes")) {
            for (JsonNode n : root.get("nodes")) {
                Node schemaNode = new Node();
                schemaNode.setId(n.has("id") ? n.get("id").asText() : UUID.randomUUID().toString());
                schemaNode.setType(n.has("type") ? n.get("type").asText() : "agent");
                schemaNode.setName(n.has("name") ? n.get("name").asText() : "Node");
                if (n.has("position")) {
                    Node.Position pos = new Node.Position();
                    pos.setX(n.get("position").has("x") ? n.get("position").get("x").asInt() : 100);
                    pos.setY(n.get("position").has("y") ? n.get("position").get("y").asInt() : 200);
                    schemaNode.setPosition(pos);
                }
                if (n.has("data")) {
                    Node.NodeData data = new Node.NodeData();
                    JsonNode d = n.get("data");
                    if (d.has("userPrompt")) data.setUserPrompt(d.get("userPrompt").asText());
                    if (d.has("systemPrompt")) data.setSystemPrompt(d.get("systemPrompt").asText());
                    if (d.has("model")) data.setModel(d.get("model").asText());
                    if (d.has("agentType")) data.setAgentType(d.get("agentType").asText());
                    if (d.has("enabledTools") && d.get("enabledTools").isArray()) {
                        List<String> tools = new ArrayList<>();
                        for (JsonNode t : d.get("enabledTools")) tools.add(t.asText());
                        data.setEnabledTools(tools);
                    }
                    if (d.has("maxToolCalls")) data.setMaxToolCalls(d.get("maxToolCalls").asInt());
                    if (d.has("toolPermissions") && d.get("toolPermissions").isArray()) {
                        List<ToolPermission> tps = new ArrayList<>();
                        for (JsonNode tpNode : d.get("toolPermissions")) {
                            ToolPermission tp = new ToolPermission();
                            if (tpNode.has("toolId")) tp.setToolId(tpNode.get("toolId").asText());
                            if (tpNode.has("enabled")) tp.setEnabled(tpNode.get("enabled").asBoolean());
                            if (tpNode.has("allowedPaths") && tpNode.get("allowedPaths").isArray()) {
                                Set<String> paths = new HashSet<>();
                                for (JsonNode p : tpNode.get("allowedPaths")) paths.add(p.asText());
                                tp.setAllowedPaths(paths);
                            }
                            tps.add(tp);
                        }
                        data.setToolPermissions(tps);
                    }
                    schemaNode.setData(data);
                }
                nodes.add(schemaNode);
            }
        }
        newSchema.setNodes(nodes);

        List<Edge> edges = new ArrayList<>();
        if (root.has("edges")) {
            for (JsonNode e : root.get("edges")) {
                Edge edge = new Edge();
                edge.setId(UUID.randomUUID().toString());
                edge.setSource(e.has("source") ? e.get("source").asText() : "");
                edge.setTarget(e.has("target") ? e.get("target").asText() : "");
                edges.add(edge);
            }
        }
        newSchema.setEdges(edges);

        // Save the new schema directly via repository
        String id = UUID.randomUUID().toString();
        newSchema.setId(id);
        newSchema.setCreatedAt(Instant.now().toString());
        newSchema.setUpdatedAt(Instant.now().toString());
        schemaRepository.save(newSchema);
        WorkflowSchema saved = newSchema;

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 75, "Creating plan tasks");
        }

        // Create a SUBSCHEMA plan linked to the generated schema
        try {
            Plan parentPlan = planService.getPlan("default");
            Plan subPlan = planService.importSchemaAsSubPlan("default", parentPlan.getId(), saved.getId());
            subPlan.setName(saved.getName());
            planService.updatePlan(subPlan);

            boolean tasksFromJson = false;
            for (var predVal : predResults.values()) {
                String predStr = predVal.toString();
                if (predStr.contains("\"tasks\"") && predStr.contains("\"title\"")) {
                    try {
                        JsonNode taskRoot = mapper.readTree(predStr);
                        if (taskRoot.has("tasks") && taskRoot.get("tasks").isArray()) {
                            for (JsonNode taskNode : taskRoot.get("tasks")) {
                                String title = taskNode.has("title") ? taskNode.get("title").asText() : "Task";
                                String desc = taskNode.has("description") ? taskNode.get("description").asText() : "";
                                String prio = taskNode.has("priority") ? taskNode.get("priority").asText() : "MEDIUM";
                                List<String> deps = new ArrayList<>();
                                if (taskNode.has("dependencies") && taskNode.get("dependencies").isArray()) {
                                    for (JsonNode dep : taskNode.get("dependencies")) deps.add(dep.asText());
                                }
                                List<String> criteria = new ArrayList<>();
                                if (taskNode.has("acceptanceCriteria") && taskNode.get("acceptanceCriteria").isArray()) {
                                    for (JsonNode ac : taskNode.get("acceptanceCriteria")) criteria.add(ac.asText());
                                }
                                planService.addTask("default", title, desc, Priority.valueOf(prio.toUpperCase()), deps, null, null);
                            }
                            tasksFromJson = true;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (!tasksFromJson) {
                for (Node schemaNode : nodes) {
                    try {
                        planService.addTask("default", schemaNode.getName(),
                                "Node in generated schema: " + saved.getName(),
                                Priority.MEDIUM, null, null, null);
                    } catch (Exception e) {
                        log.warn("Failed to create plan task for node {}: {}", schemaNode.getName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to create subplan for generated schema: {}", e.getMessage());
        }

        // Optionally write .md explanation
        String planExplanation = root.has("planExplanation") ? root.get("planExplanation").asText() : "";
        if (generateMd && !planExplanation.isBlank()) {
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 85, "Writing plan .md");
            }
            try {
                String mdContent = "# " + saved.getName() + "\n\n"
                        + saved.getDescription() + "\n\n"
                        + planExplanation + "\n\n"
                        + "## Nodes\n\n";
                for (Node n : nodes) {
                    mdContent += "- **" + n.getName() + "** (" + n.getType() + ")\n";
                }
                String mdPath = "plan_" + saved.getId().substring(0, 8) + ".md";
                utilityService.writeOutput("file", mdPath, "markdown", mdContent);
            } catch (Exception e) {
                log.warn("Failed to write plan .md: {}", e.getMessage());
            }
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 95, "Schema created");
            webSocketHandler.sendLog(schemaId, "success",
                    "SchemaBuilder: created '" + saved.getName() + "' (" + nodes.size() + " nodes)", node.getId());
        }

        return "Schema created: " + saved.getName() + " (ID: " + saved.getId() + ", " + nodes.size() + " nodes, " + edges.size() + " edges)";
    }
}
