package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmResponse;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.repository.ExecutionRepository;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Strategy for executing review-type nodes.
 * Uses ExecutionUtilityService for shared helper methods.
 */
@Component
public class ReviewNodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReviewNodeStrategy.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final ExecutionUtilityService utilityService;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final Neo4jSchemaRepository schemaRepository;
    private final ExecutionStateManager stateManager;
    private final PlanService planService;
    private final ExecutionRepository executionRepository;
    private final ReasoningCapture reasoningCapture;

    public ReviewNodeStrategy(ExecutionUtilityService utilityService,
                              LlmService llmService,
                              ExecutionWebSocketHandler webSocketHandler,
                              Neo4jSchemaRepository schemaRepository,
                              ExecutionStateManager stateManager,
                              PlanService planService,
                              ExecutionRepository executionRepository,
                              ReasoningCapture reasoningCapture) {
        this.utilityService = utilityService;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.schemaRepository = schemaRepository;
        this.stateManager = stateManager;
        this.planService = planService;
        this.executionRepository = executionRepository;
        this.reasoningCapture = reasoningCapture;
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
            passResult.put("findings", JSON_MAPPER.createArrayNode());
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
        String feedbackKey = schemaId + ":" + node.getId() + ":feedback";
        String pendingFeedback = stateManager.getNodeResults().get(schemaId) != null
                ? stateManager.getNodeResults().get(schemaId).get(feedbackKey) : null;
        String approvedKey = schemaId + ":" + node.getId() + ":approved";
        boolean isApproved = stateManager.getNodeResults().get(schemaId) != null
                && "true".equals(stateManager.getNodeResults().get(schemaId).get(approvedKey));

        // If plan was already approved by user, skip re-review and output PASS
        if (isApproved) {
            log.info("Review node {} previously approved, skipping re-review", node.getId());
            Map<String, Object> passResult = new HashMap<>();
            passResult.put("status", "PASS");
            passResult.put("findings", JSON_MAPPER.createArrayNode());
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
            planText = "No content available for review planning.";
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "warn", "Empty plan input, using placeholder", node.getId());
            }
        }

        // ── Phase 2: Analysis (premortem/prism/postmortem checks) ──
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

        // Parse analysis result
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
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(jsonStr);
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
            findingsNode = JSON_MAPPER.readTree(findingsText);
        } catch (Exception e) {
            findingsNode = JSON_MAPPER.createArrayNode();
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
                return buildResultJson("AWAITING_APPROVAL", findingsText, summary, planText, effectivePlan, null);
            }

            case "auto":
                // Auto-rewrite up to maxAutoIterations
                int autoIteration = 0;
                String currentPlan = planText;
                while (autoIteration < maxAutoIterations && planWasRewritten) {
                    autoIteration++;
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "info",
                                "Auto review iteration " + autoIteration + "/" + maxAutoIterations, node.getId());
                    }

                    // Re-run Phase 2 with rewritten plan
                    String reReviewPrompt = reviewPrompt.toString()
                            .replace(planText, rewrittenPlan != null ? rewrittenPlan : planText);
                    LlmResponse reResp = llmService.streamingChat(model, systemPrompt, reReviewPrompt, null,
                            token -> {
                                if (webSocketHandler != null) {
                                    webSocketHandler.sendToken(schemaId, node.getId(), token);
                                }
                            });
                    String reResult = reResp.text();
                    if (reResp.reasoning() != null) {
                        reasoningCapture.capture(node.getId(), reResp.reasoning());
                    }

                    // Parse re-result
                    if (reResult != null && !reResult.isBlank()) {
                        try {
                            String reJsonStr = reResult.trim();
                            int reJsonStart = reJsonStr.indexOf('{');
                            int reJsonEnd = reJsonStr.lastIndexOf('}');
                            if (reJsonStart >= 0 && reJsonEnd > reJsonStart) {
                                reJsonStr = reJsonStr.substring(reJsonStart, reJsonEnd + 1);
                            }
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode reRoot = mapper.readTree(reJsonStr);
                            String reStatus = reRoot.has("status") ? reRoot.get("status").asText() : "PASS";
                            if (reRoot.has("findings")) {
                                findingsText = reRoot.get("findings").toString();
                            }
                            if (reRoot.has("summary")) {
                                summary = reRoot.get("summary").asText();
                            }

                            if ("PASS".equals(reStatus)) {
                                // PASS before max → COMPLETED
                                resultMap.put("status", "PASS");
                                resultMap.put("plan", rewrittenPlan);
                                resultMap.put("rewriteIterations", autoIteration);
                                return buildResultJson("PASS", findingsText, summary, rewrittenPlan, null, autoIteration);
                            }

                            if (reRoot.has("rewrittenPlan")) {
                                rewrittenPlan = reRoot.get("rewrittenPlan").asText();
                                currentPlan = rewrittenPlan;
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse auto re-review: {}", e.getMessage());
                        }
                    }
                }

                // If max hit without PASS → FAILED
                if (planWasRewritten) {
                    node.setStatus(Node.NodeStatus.FAILED);
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "error",
                                "Auto review failed after " + maxAutoIterations + " iterations", node.getId());
                    }
                    return buildResultJson("FAILED", findingsText, "Max auto iterations (" + maxAutoIterations + ") reached without PASS", currentPlan, null, autoIteration);
                }

                finalResult = buildResultJson("PASS", findingsText, summary, currentPlan, null, null);
                break;

            case "hybrid":
            default: {
                // Auto-rewrite up to maxAutoIterations, then show human gate
                int hybridIteration = 0;
                String hybridPlan = planText;
                while (hybridIteration < maxAutoIterations && planWasRewritten) {
                    hybridIteration++;
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "info",
                                "Hybrid review iteration " + hybridIteration + "/" + maxAutoIterations, node.getId());
                    }

                    String hyReReviewPrompt = reviewPrompt.toString()
                            .replace(planText, rewrittenPlan != null ? rewrittenPlan : planText);
                    LlmResponse hyResp = llmService.streamingChat(model, systemPrompt, hyReReviewPrompt, null,
                            token -> {
                                if (webSocketHandler != null) {
                                    webSocketHandler.sendToken(schemaId, node.getId(), token);
                                }
                            });
                    String hyResult = hyResp.text();
                    if (hyResp.reasoning() != null) {
                        reasoningCapture.capture(node.getId(), hyResp.reasoning());
                    }

                    if (hyResult != null && !hyResult.isBlank()) {
                        try {
                            String hyJsonStr = hyResult.trim();
                            int hyJsonStart = hyJsonStr.indexOf('{');
                            int hyJsonEnd = hyJsonStr.lastIndexOf('}');
                            if (hyJsonStart >= 0 && hyJsonEnd > hyJsonStart) {
                                hyJsonStr = hyJsonStr.substring(hyJsonStart, hyJsonEnd + 1);
                            }
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode hyRoot = mapper.readTree(hyJsonStr);
                            String hyStatus = hyRoot.has("status") ? hyRoot.get("status").asText() : "PASS";
                            if (hyRoot.has("findings")) {
                                findingsText = hyRoot.get("findings").toString();
                            }
                            if (hyRoot.has("summary")) {
                                summary = hyRoot.get("summary").asText();
                            }

                            if ("PASS".equals(hyStatus)) {
                                resultMap.put("status", "PASS");
                                resultMap.put("plan", rewrittenPlan);
                                return buildResultJson("PASS", findingsText, summary, rewrittenPlan, null, null);
                            }

                            if (hyRoot.has("rewrittenPlan")) {
                                rewrittenPlan = hyRoot.get("rewrittenPlan").asText();
                                hybridPlan = rewrittenPlan;
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse hybrid re-review: {}", e.getMessage());
                        }
                    }
                }

                // Max hit → show human gate dialog (same as manual)
                if (planWasRewritten || hybridIteration >= maxAutoIterations) {
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
                        approvalPayload.put("iterationInfo", "Iteration " + hybridIteration + " of " + maxAutoIterations);
                        webSocketHandler.sendLiveUpdate(schemaId, "review_awaiting_approval", approvalPayload);
                    }

                    node.setStatus(Node.NodeStatus.AWAITING_APPROVAL);
                    return buildResultJson("AWAITING_APPROVAL", findingsText, summary, hybridPlan, rewrittenPlan, null);
                }

                finalResult = buildResultJson("PASS", findingsText, summary, null, null, null);
                break;
            }
        }

        ObjectMapper finalMapper = new ObjectMapper();
        try {
            resultMap.put("finalResult", finalResult);
            return finalMapper.writeValueAsString(resultMap);
        } catch (Exception e) {
            log.warn("Failed to serialize review result: {}", e.getMessage());
            return finalResult;
        }
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
            return JSON_MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("Failed to build review JSON: {}", e.getMessage());
            return "{\"status\":\"" + status + "\"}";
        }
    }

    private String serializeResult(Map<String, Object> resultMap) {
        try {
            return JSON_MAPPER.writeValueAsString(resultMap);
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
