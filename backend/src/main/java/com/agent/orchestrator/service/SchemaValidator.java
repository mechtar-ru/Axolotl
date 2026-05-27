package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Pipeline;
import com.agent.orchestrator.model.SchemaValidationResult;
import com.agent.orchestrator.model.Stage;
import com.agent.orchestrator.model.WorkflowSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a WorkflowSchema before execution.
 * Returns structured errors (blocking) and warnings (advisory).
 */
@Service
public class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    public SchemaValidationResult validate(WorkflowSchema schema) {
        SchemaValidationResult result = new SchemaValidationResult();
        if (schema == null) {
            result.addError("schema", "Schema is null");
            return result;
        }

        validateSchemaFields(schema, result);
        validateNodes(schema, result);
        validateEdges(schema, result);
        validatePipeline(schema, result);

        if (!result.isValid()) {
            log.warn("Schema validation failed for '{}': {} error(s), {} warning(s)",
                    schema.getName(), result.getErrors().size(), result.getWarnings().size());
        }

        return result;
    }

    private void validateSchemaFields(WorkflowSchema schema, SchemaValidationResult result) {
        if (schema.getName() == null || schema.getName().isBlank()) {
            result.addError("name", "Schema name is required");
        }
        if (schema.getDefaultModel() != null && schema.getDefaultModel().isBlank()) {
            result.addWarning("defaultModel", "Default model is set but blank — will fall back to system default");
        }
    }

    private void validateNodes(WorkflowSchema schema, SchemaValidationResult result) {
        List<Node> nodes = schema.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            result.addError("nodes", "Schema must have at least one node");
            return;
        }

        Set<String> nodeIds = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            String nodeId = node.getId();
            String nodeType = node.getType();

            // Node ID
            if (nodeId == null || nodeId.isBlank()) {
                result.addError("nodes[" + i + "].id", "Node at index " + i + " has no ID");
                continue;
            }
            if (!nodeIds.add(nodeId)) {
                result.addError("nodes[" + i + "].id", "Duplicate node ID: " + nodeId, nodeId);
            }

            // Node type
            if (nodeType == null || nodeType.isBlank()) {
                result.addError("nodes[" + i + "].type", "Node '" + nodeId + "' has no type", nodeId);
                continue;
            }

            // Type-specific validation
            Node.NodeData data = node.getData();
            switch (nodeType) {
                case "source":
                    validateSourceNode(node, data, result);
                    break;
                case "agent":
                    validateAgentNode(node, data, schema, result);
                    break;
                case "review":
                    validateReviewNode(node, data, schema, result);
                    break;
                case "verifier":
                    validateVerifierNode(node, data, result);
                    break;
                case "output":
                    validateOutputNode(node, data, result);
                    break;
                default:
                    result.addWarning("nodes[" + i + "].type", "Unknown node type '" + nodeType + "' on node '" + nodeId + "'", nodeId);
                    break;
            }
        }
    }

    private void validateSourceNode(Node node, Node.NodeData data, SchemaValidationResult result) {
        if (data == null) {
            result.addError("nodes." + node.getId() + ".data", "Source node '" + node.getId() + "' has no data", node.getId());
            return;
        }
        String sourceType = data.getConfig() != null ? (String) data.getConfig().get("sourceType") : null;
        if (sourceType == null || sourceType.isBlank()) {
            result.addWarning("nodes." + node.getId() + ".sourceType",
                    "Source node '" + node.getId() + "' has no sourceType — will produce empty input", node.getId());
            return;
        }
        switch (sourceType) {
            case "text":
                if (data.getSourceData() == null || data.getSourceData().isBlank()) {
                    result.addWarning("nodes." + node.getId() + ".sourceData",
                            "Source node '" + node.getId() + "' has sourceType=text but empty sourceData", node.getId());
                }
                break;
            case "file":
                String filePath = data.getConfig() != null ? (String) data.getConfig().get("filePath") : null;
                if (filePath == null || filePath.isBlank()) {
                    result.addError("nodes." + node.getId() + ".filePath",
                            "Source node '" + node.getId() + "' has sourceType=file but no filePath", node.getId());
                }
                break;
            case "url":
                String url = data.getConfig() != null ? (String) data.getConfig().get("url") : null;
                if (url == null || url.isBlank()) {
                    result.addError("nodes." + node.getId() + ".url",
                            "Source node '" + node.getId() + "' has sourceType=url but no URL", node.getId());
                }
                break;
            case "project":
                String projectPath = data.getConfig() != null ? (String) data.getConfig().get("projectPath") : null;
                if (projectPath == null || projectPath.isBlank()) {
                    result.addWarning("nodes." + node.getId() + ".projectPath",
                            "Source node '" + node.getId() + "' has sourceType=project but no projectPath — will list root", node.getId());
                }
                break;
        }
    }

    private void validateAgentNode(Node node, Node.NodeData data, WorkflowSchema schema, SchemaValidationResult result) {
        if (data == null) {
            result.addError("nodes." + node.getId() + ".data", "Agent node '" + node.getId() + "' has no data", node.getId());
            return;
        }
        // Model: check node-level model, then schema default
        String model = data.getModel();
        if ((model == null || model.isBlank()) && (schema.getDefaultModel() == null || schema.getDefaultModel().isBlank())) {
            result.addError("nodes." + node.getId() + ".model",
                    "Agent node '" + node.getId() + "' has no model and schema has no defaultModel", node.getId());
        }
        // System prompt (optional but recommended)
        if (data.getSystemPrompt() == null || data.getSystemPrompt().isBlank()) {
            result.addWarning("nodes." + node.getId() + ".systemPrompt",
                    "Agent node '" + node.getId() + "' has no system prompt — agent will use default", node.getId());
        }
        // Enabled tools
        if (data.getEnabledTools() == null || data.getEnabledTools().isEmpty()) {
            result.addWarning("nodes." + node.getId() + ".enabledTools",
                    "Agent node '" + node.getId() + "' has no tools enabled — agent can only text-respond", node.getId());
        }
        // Max tool calls (warning if unreasonable)
        if (data.getMaxToolCalls() <= 0) {
            result.addWarning("nodes." + node.getId() + ".maxToolCalls",
                    "Agent node '" + node.getId() + "' has maxToolCalls <= 0 — agent may loop", node.getId());
        }
    }

    private void validateReviewNode(Node node, Node.NodeData data, WorkflowSchema schema, SchemaValidationResult result) {
        if (data == null) {
            result.addError("nodes." + node.getId() + ".data", "Review node '" + node.getId() + "' has no data", node.getId());
            return;
        }
        // Model: same logic as agent
        String model = data.getModel();
        if ((model == null || model.isBlank()) && (schema.getDefaultModel() == null || schema.getDefaultModel().isBlank())) {
            result.addError("nodes." + node.getId() + ".model",
                    "Review node '" + node.getId() + "' has no model and schema has no defaultModel", node.getId());
        }
    }

    private void validateVerifierNode(Node node, Node.NodeData data, SchemaValidationResult result) {
        if (data == null) {
            result.addError("nodes." + node.getId() + ".data", "Verifier node '" + node.getId() + "' has no data", node.getId());
            return;
        }
        // Verifier typically needs a model for LLM-based checks
        // Schema default will be used at execution time, no hard error
    }

    private void validateOutputNode(Node node, Node.NodeData data, SchemaValidationResult result) {
        if (data == null) {
            result.addError("nodes." + node.getId() + ".data", "Output node '" + node.getId() + "' has no data", node.getId());
            return;
        }
        // Output nodes have few required fields, just a type check
        String outputType = data.getConfig() != null ? (String) data.getConfig().get("outputType") : null;
        if (outputType != null && !List.of("stdout", "log", "summary_report").contains(outputType)) {
            result.addWarning("nodes." + node.getId() + ".outputType",
                    "Unknown outputType '" + outputType + "' on node '" + node.getId() + "'", node.getId());
        }
    }

    private void validateEdges(WorkflowSchema schema, SchemaValidationResult result) {
        List<Node> nodes = schema.getNodes();
        if (nodes == null || nodes.isEmpty()) return;

        Set<String> nodeIds = new HashSet<>();
        for (Node n : nodes) {
            if (n.getId() != null) nodeIds.add(n.getId());
        }

        List<Edge> edges = schema.getEdges();
        if (edges == null || edges.isEmpty()) {
            result.addWarning("edges", "Schema has no edges — nodes will not be connected");
            return;
        }

        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i);
            String source = edge.getSource();
            String target = edge.getTarget();

            if (source == null || source.isBlank()) {
                result.addError("edges[" + i + "].source", "Edge at index " + i + " has no source");
            } else if (!nodeIds.contains(source)) {
                result.addError("edges[" + i + "].source", "Edge source '" + source + "' does not match any node", source);
            }

            if (target == null || target.isBlank()) {
                result.addError("edges[" + i + "].target", "Edge at index " + i + " has no target");
            } else if (!nodeIds.contains(target)) {
                result.addError("edges[" + i + "].target", "Edge target '" + target + "' does not match any node", target);
            }
        }
    }

    private void validatePipeline(WorkflowSchema schema, SchemaValidationResult result) {
        Pipeline pipeline = schema.getPipeline();
        if (pipeline == null) return; // No pipeline = blueprint execution, skip

        List<Node> nodes = schema.getNodes();
        Set<String> nodeIds = new HashSet<>();
        if (nodes != null) {
            for (Node n : nodes) {
                if (n.getId() != null) nodeIds.add(n.getId());
            }
        }

        List<Stage> stages = pipeline.getStages();
        if (stages == null || stages.isEmpty()) {
            result.addWarning("pipeline.stages", "Pipeline has no stages — pipeline execution will be a no-op");
            return;
        }

        for (int i = 0; i < stages.size(); i++) {
            Stage stage = stages.get(i);
            if (stage.getId() == null || stage.getId().isBlank()) {
                result.addError("pipeline.stages[" + i + "].id", "Stage at index " + i + " has no ID");
                continue;
            }
            if (stage.getNodeType() != null && !stage.getNodeType().isBlank()
                    && !List.of("source", "agent", "review", "verifier", "output", "transform", "custom").contains(stage.getNodeType())) {
                result.addWarning("pipeline.stages[" + i + "].nodeType",
                        "Stage '" + stage.getId() + "' has unknown nodeType '" + stage.getNodeType() + "'");
            }
            if (stage.getDependencies() != null) {
                for (String dep : stage.getDependencies()) {
                    boolean found = stages.stream().anyMatch(s -> dep.equals(s.getId()));
                    if (!found) {
                        result.addError("pipeline.stages[" + i + "].dependencies",
                                "Stage '" + stage.getId() + "' depends on '" + dep + "' which does not exist");
                    }
                }
            }
        }
    }
}
