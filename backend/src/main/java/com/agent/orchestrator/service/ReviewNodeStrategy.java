package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.NodeExecution;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy for executing review-type nodes.
 * Uses ExecutionUtilityService for shared helper methods.
 */
@Component
public class ReviewNodeStrategy implements NodeExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReviewNodeStrategy.class);

    private final ExecutionUtilityService utilityService;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final Neo4jSchemaRepository schemaRepository;
    private final ExecutionStateManager stateManager;
    private final PlanService planService;
    private final ExecutionRepository executionRepository;
    private final ReasoningCapture reasoningCapture;
    private final ObjectMapper objectMapper;

    // ── Inner class for iteration results ──

    private static class ReviewIterationResult {
        String status = "PASS";
        String findings = "[]";
        String summary = "";
        String rewrittenPlan;
    }

    public ReviewNodeStrategy(ExecutionUtilityService utilityService,
                              LlmService llmService,
                              ExecutionWebSocketHandler webSocketHandler,
                              Neo4jSchemaRepository schemaRepository,
                              ExecutionStateManager stateManager,
                              PlanService planService,
                              ExecutionRepository executionRepository,
                              ReasoningCapture reasoningCapture,
                              ObjectMapper objectMapper) {
        this.utilityService = utilityService;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.schemaRepository = schemaRepository;
        this.stateManager = stateManager;
        this.planService = planService;
        this.executionRepository = executionRepository;
        this.reasoningCapture = reasoningCapture;
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedNodeType() {
        return "review";
    }

    @Override
    public Map<String, Object> executeNode(Node node, NodeExecution nodeExec, WorkflowSchema schema,
                                             List<Node> allNodes, List<Edge> edges,
                                             Map<String, Object> executionContext, String schemaId) {
        String resolvedModel = (String) executionContext.getOrDefault("model", "");
        String result = executeReviewNode(node, schemaId, resolvedModel);
        return Map.of("result", result);
    }

    // ────────────────────────── review execution ──────────────────────────

    public String executeReviewNode(Node node, String schemaId, String resolvedModel) {
        // Collect predecessor results (the upstream plan/context)
        Map<String, Object> predResults = utilityService.collectPredecessorResults(
                schemaRepository.findById(schemaId), node.getId());
        String inputContent = predResults.values().stream()
                .findFirst().map(Object::toString).orElse("");

        // Extract review config from node config
        Map<String, Object> config = node.getData() != null && node.getData().getConfig() != null
                ? node.getData().getConfig() : new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> checks = config.get("checks") instanceof Map
                ? (Map<String, Object>) config.get("checks") : new HashMap<>();

        boolean premortem = checks.get("premortem") instanceof Boolean
                ? (Boolean) checks.get("premortem") : true;
        boolean prism = checks.get("prism") instanceof Boolean
                ? (Boolean) checks.get("prism") : false;
        boolean postmortem = checks.get("postmortem") instanceof Boolean
                ? (Boolean) checks.get("postmortem") : false;
        boolean generatePlan = config.get("generatePlan") instanceof Boolean
                ? (Boolean) config.get("generatePlan") : true;
        String mode = config.get("mode") instanceof String
                ? (String) config.get("mode") : "manual";
        int maxAutoIterations = config.get("maxAutoIterations") instanceof Number
                ? ((Number) config.get("maxAutoIterations")).intValue() : 3;

        // Check if schema has autoApproveDrafts enabled — skip review
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema != null && schema.isAutoApproveDrafts()) {
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50,
                        "Auto-approve: drafts complete, skipping review");
                webSocketHandler.sendLog(schemaId, "info",
                        "autoApproveDrafts=true — review auto-approved", node.getId());
            }
            Map<String, Object> passResult = new HashMap<>();
            passResult.put("status", "PASS");
            passResult.put("findings", objectMapper.createArrayNode());
            passResult.put("summary", "Drafts auto-approved (autoApproveDrafts=true)");
            passResult.put("plan", inputContent);
            passResult.put("originalInput", inputContent);
            passResult.put("mode", mode);
            passResult.put("finalResult", buildResultJson("PASS", "[]",
                    "Drafts auto-approved", inputContent, null, null));
            return serializeResult(passResult);
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 10,
                    "Review: collecting context");
            webSocketHandler.sendLog(schemaId, "info",
                    "Review: premortem=" + premortem + ", prism=" + prism
                    + ", postmortem=" + postmortem + ", mode=" + mode
                    + ", generatePlan=" + generatePlan, node.getId());
        }

        String model = resolvedModel;
        if (model == null || model.isBlank()) {
            model = utilityService.resolveModel(node.getData() != null ? node.getData().getModel() : null,
                    null, null, null);
        }

        // Check for pending feedback or approval in execution state
        Map<String, String> nodeResults = stateManager.getNodeResults().get(schemaId);
        String feedbackKey = schemaId + ":" + node.getId() + ":feedback";
        String pendingFeedback = nodeResults != null ? nodeResults.get(feedbackKey) : null;
        String approvedKey = schemaId + ":" + node.getId() + ":approved";
        boolean isApproved = nodeResults != null && "true".equals(nodeResults.remove(approvedKey));

        // If plan was already approved by user, skip re-review and output PASS
        if (isApproved) {
            log.info("Review node {} previously approved, skipping re-review", node.getId());
            Map<String, Object> passResult = new HashMap<>();
            passResult.put("status", "PASS");
            passResult.put("findings", objectMapper.createArrayNode());
            passResult.put("summary", "Plan was approved by user");
            passResult.put("plan", inputContent);
            passResult.put("originalInput", inputContent);
            passResult.put("mode", mode);
            passResult.put("finalResult", buildResultJson("PASS", "[]",
                    "Plan was approved by user", inputContent, null, null));
            cleanApprovalFlag(schemaId, node.getId(), stateManager);
            return serializeResult(passResult);
        }

        // ── Phase 1: Plan Generation ──
        String planText = generatePlan(model, inputContent, pendingFeedback, generatePlan, schemaId, node);

        // ── Phase 2: Analysis ──
        Map<String, Object> analysisData = runAnalysis(model, planText, premortem, prism, postmortem, schemaId, node);
        String analysisResult = (String) analysisData.get("analysisResult");
        String reviewPromptStr = (String) analysisData.get("reviewPrompt");
        String systemPrompt = (String) analysisData.get("systemPrompt");

        // ── Phase 3: Result Parsing & Mode Handling ──
        Map<String, Object> resultMap = parseAndHandleResult(analysisResult, planText,
                inputContent, mode, maxAutoIterations, schemaId, node, model,
                reviewPromptStr, systemPrompt);
        return serializeResult(resultMap);
    }

    // ── Phase 1: Plan Generation ──

    private String generatePlan(String model, String inputContent, String pendingFeedback,
                                 boolean generatePlan, String schemaId, Node node) {
        String planText;
        if (generatePlan) {
            if (webSocketHandler != null) {
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20,
                        "Review Phase 1: generating plan");
                webSocketHandler.sendLog(schemaId, "info", "Review: generating plan from upstream", node.getId());
            }
            String planPrompt = "Create a detailed implementation plan from this description: " + inputContent;
            if (pendingFeedback != null && !pendingFeedback.isBlank()) {
                planPrompt += "\n\nThe user provided the following feedback on the previous plan: " + pendingFeedback + ". Incorporate it.";
            }
            String planSystemPrompt = "You are a structured planner. Create a clear, detailed implementation plan.";
            LlmResponse planResp = llmService.streamingChat(model, planSystemPrompt, planPrompt, null,
                    token -> {
                        if (webSocketHandler != null) {
                            webSocketHandler.sendToken(schemaId, node.getId(), token);
                        }
                    });
            planText = planResp.text();
            if (planResp.reasoning() != null) {
                reasoningCapture.capture(node.getId(), planResp.reasoning());
            }
        } else {
            planText = inputContent;
        }

        if (planText == null || planText.isBlank()) {
            log.error("Review planning returned empty plan for schema {}", schemaId);
            node.setStatus(Node.NodeStatus.FAILED);
            return "";
        }
        return planText;
    }

    // ── Phase 2: Analysis ──

    private Map<String, Object> runAnalysis(String model, String planText,
                                             boolean premortem, boolean prism, boolean postmortem,
                                             String schemaId, Node node) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50,
                    "Review Phase 2: analyzing plan");
            webSocketHandler.sendLog(schemaId, "info", "Review: analyzing plan", node.getId());
        }

        StringBuilder reviewPrompt = new StringBuilder();
        reviewPrompt.append("You are a code/plan reviewer. Review the following plan and provide findings.\n\n");

        if (premortem) {
            reviewPrompt.append("=== PREMORTEM CHECK ===\n");
            reviewPrompt.append("Analyze the plan before execution. Identify:\n");
            reviewPrompt.append("- Potential risks and failure points\n");
            reviewPrompt.append("- Missing edge cases\n");
            reviewPrompt.append("- Resource or dependency issues\n");
            reviewPrompt.append("- Timeline or ordering concerns\n\n");
        }

        if (prism) {
            reviewPrompt.append("=== PRISM CHECK (Codebase Context) ===\n");
            reviewPrompt.append("Consider the existing codebase structure, patterns, and conventions.\n");
            reviewPrompt.append("Note any inconsistencies with the established architecture.\n\n");
        }

        if (postmortem) {
            reviewPrompt.append("=== POSTMORTEM CHECK ===\n");
            reviewPrompt.append("Analyze execution history and past outcomes.\n");
            reviewPrompt.append("Consider what went well and what could be improved.\n\n");
        }

        reviewPrompt.append("=== PLAN TO REVIEW ===\n");
        reviewPrompt.append(planText).append("\n\n");

        reviewPrompt.append("=== OUTPUT FORMAT ===\n");
        reviewPrompt.append("Respond in strict JSON format (no markdown fences):\n");
        reviewPrompt.append("{\n");
        reviewPrompt.append("  \"status\": \"PASS\" or \"REWRITE\",\n");
        reviewPrompt.append("  \"findings\": [\n");
        reviewPrompt.append("    {\"severity\": \"critical\"|\"warning\"|\"info\", \"message\": \"...\", \"suggestion\": \"...\"}\n");
        reviewPrompt.append("  ],\n");
        reviewPrompt.append("  \"summary\": \"Overall assessment\"\n");
        reviewPrompt.append("}\n");

        // Always request rewrittenPlan so we have the option
        reviewPrompt.append("\nInclude a 'rewrittenPlan' field in the response with your improved version of the plan if status is REWRITE.\n");

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 60,
                    "Review: calling LLM for analysis");
            webSocketHandler.sendLog(schemaId, "info", "Review: sending to LLM for analysis", node.getId());
        }

        String systemPrompt = "You are a structured reviewer. Analyze the provided plan and return findings in JSON format.";
                    LlmResponse analysisResp = llmService.streamingChat(model, systemPrompt, reviewPrompt.toString(), null,
                token -> {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendToken(schemaId, node.getId(), token);
                    }
                });
            String analysisResult = analysisResp.text();
            if (analysisResp.reasoning() != null) {
                reasoningCapture.capture(node.getId(), analysisResp.reasoning());
            }

        Map<String, Object> result = new HashMap<>();
        result.put("analysisResult", analysisResult);
        result.put("reviewPrompt", reviewPrompt.toString());
        result.put("systemPrompt", systemPrompt);
        return result;
    }

    /**
     * Run a single review iteration: call LLM → parse JSON → return status/findings/summary/rewrittenPlan.
     * Shared between auto and hybrid modes to eliminate code duplication.
     */
    private ReviewIterationResult runReviewIteration(String systemPrompt, String reviewPrompt,
                                                      String schemaId, String model, Node node,
                                                      String originalPlanText, String currentRewrittenPlan) {
        ReviewIterationResult result = new ReviewIterationResult();
        String iterationPrompt = reviewPrompt.replace(originalPlanText,
                currentRewrittenPlan != null ? currentRewrittenPlan : originalPlanText);
        LlmResponse resp = llmService.streamingChat(model, systemPrompt, iterationPrompt, null,
                token -> {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendToken(schemaId, node.getId(), token);
                    }
                });
        String respText = resp.text();
        if (resp.reasoning() != null) {
            reasoningCapture.capture(node.getId(), resp.reasoning());
        }
        if (respText != null && !respText.isBlank()) {
            try {
                String jsonStr = respText.trim();
                int jsonStart = jsonStr.indexOf('{');
                int jsonEnd = jsonStr.lastIndexOf('}');
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    jsonStr = jsonStr.substring(jsonStart, jsonEnd + 1);
                }
                JsonNode root = objectMapper.readTree(jsonStr);
                result.status = root.has("status") ? root.get("status").asText() : "PASS";
                if (root.has("findings")) result.findings = root.get("findings").toString();
                if (root.has("summary")) result.summary = root.get("summary").asText();
                if (root.has("rewrittenPlan")) result.rewrittenPlan = root.get("rewrittenPlan").asText();
            } catch (Exception e) {
                log.warn("Failed to parse re-review JSON: {}", e.getMessage());
            }
        }
        return result;
    }

    // ── Phase 3: Result Parsing & Mode Handling ──

    private Map<String, Object> parseAndHandleResult(String analysisResult, String planText,
                                                      String inputContent, String mode, int maxAutoIterations,
                                                      String schemaId, Node node,
                                                      String model,
                                                      String reviewPromptStr, String systemPrompt) {
        String reviewStatus = "PASS";
        String findingsText = "[]";
        String rewrittenPlan = null;
        String summary = "";

        if (analysisResult != null && !analysisResult.isBlank()) {
            try {
                String jsonStr = analysisResult.trim();
                int jsonStart = jsonStr.indexOf('{');
                int jsonEnd = jsonStr.lastIndexOf('}');
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    jsonStr = jsonStr.substring(jsonStart, jsonEnd + 1);
                }
                JsonNode root = objectMapper.readTree(jsonStr);
                if (root.has("status")) {
                    reviewStatus = root.get("status").asText();
                }
                if (root.has("findings")) {
                    findingsText = root.get("findings").toString();
                }
                if (root.has("summary")) {
                    summary = root.get("summary").asText();
                }
                if (root.has("rewrittenPlan")) {
                    rewrittenPlan = root.get("rewrittenPlan").asText();
                }
            } catch (Exception e) {
                log.warn("Failed to parse review analysis JSON: {}", e.getMessage());
            }
        }

        // Parse findings string to JSON node for proper serialization
        JsonNode findingsNode;
        try {
            findingsNode = objectMapper.readTree(findingsText);
        } catch (Exception e) {
            findingsNode = objectMapper.createArrayNode();
        }

        // Build structured result
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", reviewStatus);
        resultMap.put("findings", findingsNode);
        resultMap.put("summary", summary);
        resultMap.put("plan", planText);
        resultMap.put("originalInput", inputContent);
        resultMap.put("mode", mode);

        // ── Mode Handling ──
        boolean planWasRewritten = "REWRITE".equals(reviewStatus) && rewrittenPlan != null;
        String finalResult;

        switch (mode) {
            case "manual": {
                // Always show human approval dialog (PASS or REWRITE)
                String effectivePlan = (rewrittenPlan != null && !rewrittenPlan.isBlank()) ? rewrittenPlan : planText;
                resultMap.put("rewrittenPlan", effectivePlan);
                resultMap.put("requiresApproval", true);

                // Emit review_awaiting_approval WS event
                if (webSocketHandler != null) {
                    Map<String, Object> approvalPayload = new HashMap<>();
                    approvalPayload.put("nodeId", node.getId());
                    approvalPayload.put("status", "AWAITING_APPROVAL");
                    approvalPayload.put("plan", planText);
                    approvalPayload.put("rewrittenPlan", effectivePlan);
                    approvalPayload.put("findings", findingsNode);
                    approvalPayload.put("summary", summary);
                    approvalPayload.put("mode", "manual");
                    approvalPayload.put("iterationInfo", "Requires your approval");
                    webSocketHandler.sendLiveUpdate(schemaId, "review_awaiting_approval", approvalPayload);
                }

                // Set node to AWAITING_APPROVAL
                node.setStatus(Node.NodeStatus.AWAITING_APPROVAL);

                // Store result so downstream can pick up when approved
                resultMap.put("finalResult", buildResultJson("AWAITING_APPROVAL", findingsText, summary, planText, effectivePlan, null));
                return resultMap;
            }

            case "auto": {
                // Auto-rewrite up to maxAutoIterations
                int iteration = 0;
                String currentPlan = planText;
                while (iteration < maxAutoIterations && planWasRewritten) {
                    iteration++;
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "info",
                                "Auto review iteration " + iteration + "/" + maxAutoIterations, node.getId());
                    }

                    ReviewIterationResult iterResult = runReviewIteration(
                            systemPrompt, reviewPromptStr, schemaId, model, node,
                            planText, rewrittenPlan);

                    findingsText = iterResult.findings;
                    summary = iterResult.summary;

                    if ("PASS".equals(iterResult.status)) {
                        // PASS before max → COMPLETED
                        resultMap.put("status", "PASS");
                        resultMap.put("plan", rewrittenPlan);
                        resultMap.put("rewriteIterations", iteration);
                        resultMap.put("finalResult", buildResultJson("PASS", findingsText, summary, rewrittenPlan, null, iteration));
                        return resultMap;
                    }

                    if (iterResult.rewrittenPlan != null) {
                        rewrittenPlan = iterResult.rewrittenPlan;
                        currentPlan = rewrittenPlan;
                    }
                }

                // If max hit without PASS → FAILED
                if (planWasRewritten) {
                    node.setStatus(Node.NodeStatus.FAILED);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "error",
                                "Max auto iterations (" + maxAutoIterations + ") reached without PASS", node.getId());
                    }
                    resultMap.put("finalResult", buildResultJson("FAILED", findingsText,
                            "Max auto iterations (" + maxAutoIterations + ") reached without PASS", currentPlan, null, iteration));
                    return resultMap;
                }

                finalResult = buildResultJson("PASS", findingsText, summary, currentPlan, null, null);
                break;
            }

            case "hybrid":
            default: {
                // Auto-rewrite up to maxAutoIterations, then show human gate
                int iteration = 0;
                String hybridPlan = planText;
                while (iteration < maxAutoIterations && planWasRewritten) {
                    iteration++;
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "info",
                                "Hybrid review iteration " + iteration + "/" + maxAutoIterations, node.getId());
                    }

                    ReviewIterationResult iterResult = runReviewIteration(
                            systemPrompt, reviewPromptStr, schemaId, model, node,
                            planText, rewrittenPlan);

                    findingsText = iterResult.findings;
                    summary = iterResult.summary;

                    if ("PASS".equals(iterResult.status)) {
                        resultMap.put("status", "PASS");
                        resultMap.put("plan", rewrittenPlan);
                        resultMap.put("finalResult", buildResultJson("PASS", findingsText, summary, rewrittenPlan, null, null));
                        return resultMap;
                    }

                    if (iterResult.rewrittenPlan != null) {
                        rewrittenPlan = iterResult.rewrittenPlan;
                        hybridPlan = rewrittenPlan;
                    }
                }

                // Max hit → show human gate dialog (same as manual)
                if (planWasRewritten || iteration >= maxAutoIterations) {
                    resultMap.put("requiresApproval", true);
                    resultMap.put("rewrittenPlan", rewrittenPlan != null ? rewrittenPlan : hybridPlan);

                    if (webSocketHandler != null) {
                        Map<String, Object> approvalPayload = new HashMap<>();
                        approvalPayload.put("nodeId", node.getId());
                        approvalPayload.put("status", "AWAITING_APPROVAL");
                        approvalPayload.put("plan", hybridPlan);
                        approvalPayload.put("rewrittenPlan", rewrittenPlan);
                        approvalPayload.put("findings", findingsNode);
                        approvalPayload.put("summary", summary);
                        approvalPayload.put("mode", "hybrid");
                        approvalPayload.put("iterationInfo", "Iteration " + iteration + " of " + maxAutoIterations);
                        webSocketHandler.sendLiveUpdate(schemaId, "review_awaiting_approval", approvalPayload);
                    }

                    node.setStatus(Node.NodeStatus.AWAITING_APPROVAL);
                    resultMap.put("finalResult", buildResultJson("AWAITING_APPROVAL", findingsText, summary, hybridPlan, rewrittenPlan, null));
                    return resultMap;
                }

                finalResult = buildResultJson("PASS", findingsText, summary, null, null, null);
                break;
            }
        }

        resultMap.put("finalResult", finalResult);
        return resultMap;
    }

    private String buildResultJson(String status, String findings, String summary, String plan, String rewrittenPlan, Integer rewriteIterations) {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("status", status);
            map.put("findings", findings);
            if (summary != null) map.put("summary", summary);
            if (plan != null) map.put("plan", plan);
            if (rewrittenPlan != null && !rewrittenPlan.isBlank()) map.put("rewrittenPlan", rewrittenPlan);
            if (rewriteIterations != null) map.put("rewriteIterations", rewriteIterations);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to build review JSON: {}", e.getMessage());
            return "{\"status\":\"" + status + "\"}";
        }
    }

    private String serializeResult(Map<String, Object> resultMap) {
        try {
            return objectMapper.writeValueAsString(resultMap);
        } catch (Exception e) {
            log.warn("Failed to serialize review result: {}", e.getMessage());
            return "{\"status\":\"ERROR\"}";
        }
    }

    private void cleanApprovalFlag(String schemaId, String nodeId, ExecutionStateManager stateManager) {
        try {
            String approvedKey = schemaId + ":" + nodeId + ":approved";
            Map<String, String> nodeResults = stateManager.getNodeResults().get(schemaId);
            if (nodeResults != null) {
                nodeResults.remove(approvedKey);
            }
        } catch (Exception e) {
            log.warn("Failed to clean approval flag: {}", e.getMessage());
        }
    }
}
