package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Edge;
import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.Pipeline;
import com.agent.orchestrator.model.Stage;
import com.agent.orchestrator.model.WorkflowSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Factory for creating pipeline templates and expanding stages.
 * All methods are static and stateless — no injected dependencies.
 */
public final class PipelineFactory {

    private static final Logger log = LoggerFactory.getLogger(PipelineFactory.class);

    private PipelineFactory() {
        // utility class
    }

    /**
     * Create the default 9-stage pipeline with draft phases.
     */
    public static Pipeline createDefaultPipeline(String appType, String description) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("default-pipeline");
        pipeline.setName("Default Pipeline");
        pipeline.setDescription("Default 9-stage pipeline with draft phases for " + appType);
        pipeline.setParallelStrategy("sequential");
        pipeline.setTddEnabled(false);

        List<Stage> stages = new ArrayList<>();

        Stage source = new Stage();
        source.setId("receive-1");
        source.setName("Receive");
        source.setNodeType("source");
        source.setSystemPrompt("Receive and process input for: " + description);
        source.setPositionX(50);
        source.setPositionY(200);
        stages.add(source);

        // ── Draft stages (spec → plan → ui → backend) ──
        Stage draftSpec = new Stage();
        draftSpec.setId("draft-spec");
        draftSpec.setName("Draft Spec");
        draftSpec.setNodeType("draft");
        draftSpec.setDependencies(List.of("receive-1"));
        draftSpec.setSystemPrompt("Generate a specification document for: " + description);
        draftSpec.setPositionX(350);
        draftSpec.setPositionY(200);
        Map<String, Object> draftSpecConfig = new HashMap<>();
        draftSpecConfig.put("draftType", "spec");
        draftSpec.setConfig(draftSpecConfig);
        stages.add(draftSpec);

        Stage draftPlan = new Stage();
        draftPlan.setId("draft-plan");
        draftPlan.setName("Draft Plan");
        draftPlan.setNodeType("draft");
        draftPlan.setDependencies(List.of("draft-spec"));
        draftPlan.setSystemPrompt("Generate an implementation plan from the spec for: " + description);
        draftPlan.setPositionX(550);
        draftPlan.setPositionY(200);
        Map<String, Object> draftPlanConfig = new HashMap<>();
        draftPlanConfig.put("draftType", "plan");
        draftPlan.setConfig(draftPlanConfig);
        stages.add(draftPlan);

        Stage draftUi = new Stage();
        draftUi.setId("draft-ui");
        draftUi.setName("Draft UI");
        draftUi.setNodeType("draft");
        draftUi.setDependencies(List.of("draft-plan"));
        draftUi.setSystemPrompt("Generate an OpenUI YAML spec from the plan for: " + description);
        draftUi.setPositionX(750);
        draftUi.setPositionY(200);
        Map<String, Object> draftUiConfig = new HashMap<>();
        draftUiConfig.put("draftType", "ui");
        draftUi.setConfig(draftUiConfig);
        stages.add(draftUi);

        Stage draftBackend = new Stage();
        draftBackend.setId("draft-backend");
        draftBackend.setName("Draft Backend");
        draftBackend.setNodeType("draft");
        draftBackend.setDependencies(List.of("draft-ui"));
        draftBackend.setSystemPrompt("Generate backend module architecture from the UI spec for: " + description);
        draftBackend.setPositionX(950);
        draftBackend.setPositionY(200);
        Map<String, Object> draftBackendConfig = new HashMap<>();
        draftBackendConfig.put("draftType", "backend");
        draftBackend.setConfig(draftBackendConfig);
        stages.add(draftBackend);

        Stage review = new Stage();
        review.setId("review-1");
        review.setName("Review Plan");
        review.setNodeType("review");
        review.setDependencies(List.of("draft-backend"));
        review.setSystemPrompt("Review the plan for: " + description);
        review.setPositionX(1150);
        review.setPositionY(200);
        stages.add(review);

        Stage agent = new Stage();
        agent.setId("think-1");
        agent.setName("Execute");
        agent.setNodeType("agent");
        agent.setDependencies(List.of("review-1"));
        agent.setSystemPrompt("Execute the plan for: " + description);
        agent.setPositionX(1450);
        agent.setPositionY(200);
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put("tools", List.of("file_read", "file_write", "bash", "grep", "directory_read"));
        agent.setConfig(agentConfig);
        stages.add(agent);

        Stage verifier = new Stage();
        verifier.setId("verify-1");
        verifier.setName("Verify");
        verifier.setNodeType("verifier");
        verifier.setDependencies(List.of("think-1"));
        verifier.setSystemPrompt("Verify the results for: " + description);
        verifier.setPositionX(1750);
        verifier.setPositionY(200);
        stages.add(verifier);

        Stage output = new Stage();
        output.setId("act-1");
        output.setName("Output");
        output.setNodeType("output");
        output.setDependencies(List.of("verify-1"));
        output.setSystemPrompt("Output the results for: " + description);
        output.setPositionX(2050);
        output.setPositionY(200);
        stages.add(output);

        pipeline.setStages(stages);
        return pipeline;
    }

    /**
     * Create a full 4-phase app-creation pipeline: Design → Plan → Implement → Document.
     * Phases: source → review(design) → planner → review(plan) → prep → agent → verifier → doc-agent → output
     */
    public static Pipeline createAppPipeline(String appType, String description) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("app-pipeline");
        pipeline.setName("App Creation Pipeline");
        pipeline.setDescription("Full 4-phase app creation workflow for " + appType);
        pipeline.setParallelStrategy("sequential");
        pipeline.setTddEnabled(false);

        List<Stage> stages = new ArrayList<>();

        int yCenter = 200;
        int xSpacing = 220;

        // 1. Receive
        Stage source = new Stage();
        source.setId("receive-1");
        source.setName("Receive");
        source.setNodeType("source");
        source.setSystemPrompt("Receive and process input for: " + description);
        source.setPositionX(50);
        source.setPositionY(yCenter);
        stages.add(source);

        // 2. Design Review
        Stage designReview = new Stage();
        designReview.setId("review-design");
        designReview.setName("Design Review");
        designReview.setNodeType("review");
        designReview.setDependencies(List.of("receive-1"));
        designReview.setSystemPrompt("Review the design document for completeness, " +
                "identify gaps, and suggest improvements for: " + description);
        Map<String, Object> designReviewConfig = new HashMap<>();
        designReviewConfig.put("mode", "manual");
        designReviewConfig.put("checks", Map.of("premortem", true, "prism", false, "postmortem", false));
        designReview.setConfig(designReviewConfig);
        designReview.setPositionX(50 + xSpacing);
        designReview.setPositionY(yCenter);
        stages.add(designReview);

        // 3. Planner
        Stage planner = new Stage();
        planner.setId("planner-1");
        planner.setName("Plan");
        planner.setNodeType("agent");
        planner.setDependencies(List.of("review-design"));
        planner.setModel(""); // will use default model
        planner.setSystemPrompt("You are a planner. Based on the approved design, " +
                "create a detailed implementation plan with numbered steps and dependencies. " +
                "Output the plan as a structured list where each step has: id, title, description, depends_on. " +
                "Wrap the plan in ```plan ... ``` markers. Application: " + description);
        Map<String, Object> plannerConfig = new HashMap<>();
        plannerConfig.put("agentType", "planner");
        planner.setConfig(plannerConfig);
        planner.setPositionX(50 + xSpacing * 2);
        planner.setPositionY(yCenter);
        stages.add(planner);

        // 4. Plan Review
        Stage planReview = new Stage();
        planReview.setId("review-plan");
        planReview.setName("Plan Review");
        planReview.setNodeType("review");
        planReview.setDependencies(List.of("planner-1"));
        planReview.setSystemPrompt("Review the implementation plan. Check for completeness, " +
                "correct dependency ordering, and feasibility for: " + description);
        Map<String, Object> planReviewConfig = new HashMap<>();
        planReviewConfig.put("mode", "manual");
        planReviewConfig.put("checks", Map.of("premortem", true, "prism", false, "postmortem", false));
        planReview.setConfig(planReviewConfig);
        planReview.setPositionX(50 + xSpacing * 3);
        planReview.setPositionY(yCenter);
        stages.add(planReview);

        // 5. Prep (pseudocode + tests)
        Stage prep = new Stage();
        prep.setId("prep-1");
        prep.setName("Prep");
        prep.setNodeType("agent");
        prep.setDependencies(List.of("review-plan"));
        prep.setModel("");
        prep.setSystemPrompt("You are a preparation agent for " + appType + ". " +
                "Based on the approved plan, generate:\n" +
                "1. Pseudocode for frontend components (plan/pseudo-frontend.md)\n" +
                "2. Pseudocode for backend/API (plan/pseudo-backend.md)\n" +
                "3. Test stubs based on the pseudocode API\n\n" +
                "Write these files to disk using file_write. " +
                "Application: " + description);
        Map<String, Object> prepConfig = new HashMap<>();
        prepConfig.put("agentType", "prep");
        prepConfig.put("enabledTools", List.of("file_write", "file_read", "directory_read"));
        prep.setConfig(prepConfig);
        prep.setPositionX(50 + xSpacing * 4);
        prep.setPositionY(yCenter);
        stages.add(prep);

        // 6. Agent (implementation)
        Stage agent = new Stage();
        agent.setId("impl-1");
        agent.setName("Implement");
        agent.setNodeType("agent");
        agent.setDependencies(List.of("prep-1"));
        agent.setModel("");
        agent.setSystemPrompt("Implement the application " + appType + " based on the approved plan. " +
                "Read plan steps, follow pseudocode contracts, write code files. " +
                "Application description: " + description);
        Map<String, Object> agentConfig = new HashMap<>();
        agentConfig.put("agentType", "code-agent");
        agentConfig.put("tools", List.of("file_read", "file_write", "bash", "grep", "directory_read"));
        agent.setConfig(agentConfig);
        agent.setPositionX(50 + xSpacing * 5);
        agent.setPositionY(yCenter);
        stages.add(agent);

        // 7. Verifier
        Stage verifier = new Stage();
        verifier.setId("verify-1");
        verifier.setName("Verify");
        verifier.setNodeType("verifier");
        verifier.setDependencies(List.of("impl-1"));
        verifier.setSystemPrompt("Verify the implementation for " + appType + ". " +
                "Check: tests pass, code follows plan and design, no gaps remain. " +
                "Description: " + description);
        verifier.setPositionX(50 + xSpacing * 6);
        verifier.setPositionY(yCenter);
        stages.add(verifier);

        // 8. Doc-Agent
        Stage docAgent = new Stage();
        docAgent.setId("doc-1");
        docAgent.setName("Document");
        docAgent.setNodeType("agent");
        docAgent.setDependencies(List.of("verify-1"));
        docAgent.setModel("");
        docAgent.setSystemPrompt("You are a documentation agent for " + appType + ". " +
                "Update project documentation. Read existing docs, append to spec.md and changelog.md, " +
                "create design docs for new features. See stage outputs for results. " +
                "Application: " + description);
        Map<String, Object> docConfig = new HashMap<>();
        docConfig.put("agentType", "doc-agent");
        docConfig.put("enabledTools", List.of("file_read", "file_write", "directory_read"));
        docAgent.setConfig(docConfig);
        docAgent.setPositionX(50 + xSpacing * 7);
        docAgent.setPositionY(yCenter);
        stages.add(docAgent);

        // 9. Output
        Stage output = new Stage();
        output.setId("output-1");
        output.setName("Output");
        output.setNodeType("output");
        output.setDependencies(List.of("doc-1"));
        output.setSystemPrompt("Output the final results for: " + description);
        output.setPositionX(50 + xSpacing * 8);
        output.setPositionY(yCenter);
        stages.add(output);

        pipeline.setStages(stages);
        return pipeline;
    }

    /**
     * Create a minimal pipeline: source → agent → verifier → output
     */
    public static Pipeline createMinimalPipeline(String appType, String description) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("minimal-pipeline");
        pipeline.setName("Minimal Pipeline");
        pipeline.setDescription("Minimal 4-stage pipeline for " + appType);
        pipeline.setParallelStrategy("sequential");
        pipeline.setTddEnabled(false);

        List<Stage> stages = new ArrayList<>();

        Stage source = new Stage();
        source.setId("receive-1");
        source.setName("Receive");
        source.setNodeType("source");
        source.setSystemPrompt("Receive input for: " + description);
        source.setPositionX(50);
        source.setPositionY(200);
        stages.add(source);

        Stage agent = new Stage();
        agent.setId("impl-1");
        agent.setName("Implement");
        agent.setNodeType("agent");
        agent.setDependencies(List.of("receive-1"));
        agent.setSystemPrompt("Implement: " + description);
        agent.setPositionX(300);
        agent.setPositionY(200);
        stages.add(agent);

        Stage verifier = new Stage();
        verifier.setId("verify-1");
        verifier.setName("Verify");
        verifier.setNodeType("verifier");
        verifier.setDependencies(List.of("impl-1"));
        verifier.setSystemPrompt("Verify: " + description);
        verifier.setPositionX(550);
        verifier.setPositionY(200);
        stages.add(verifier);

        Stage output = new Stage();
        output.setId("output-1");
        output.setName("Output");
        output.setNodeType("output");
        output.setDependencies(List.of("verify-1"));
        output.setSystemPrompt("Output results for: " + description);
        output.setPositionX(800);
        output.setPositionY(200);
        stages.add(output);

        pipeline.setStages(stages);
        return pipeline;
    }

    /**
     * When {@code pipeline.tddEnabled == true}, expands each agent → verifier stage pair
     * into a 4-stage TDD block: test → verify-test → impl → verify.
     * <p>
     * Dependencies: verify-test depends on test; impl depends on test (not verify-test);
     * verify depends on impl. This enables parallel execution of verify-test and impl
     * once test completes.
     * <p>
     * Stages that depended on the original agent are rewritten to depend on impl;
     * stages that depended on the original verifier are rewritten to depend on the new verify.
     * <p>
     * No-op when {@code tddEnabled == false} or when no agent → verifier pairs are found.
     */
    public static void expandTddStages(Pipeline pipeline) {
        if (pipeline == null || !pipeline.isTddEnabled()) return;

        List<Stage> stages = new ArrayList<>(pipeline.getStages());
        List<Stage> expanded = new ArrayList<>();
        Map<String, String> depRewrites = new HashMap<>();
        Set<String> skipIds = new HashSet<>();

        for (Stage s : stages) {
            if (skipIds.contains(s.getId())) continue;

            Stage verifier = findVerifierForAgent(stages, s);

            if (verifier != null) {
                String x = s.getId(); // e.g., "think-1"

                // test-X — same deps as the original agent (e.g., depends on review)
                Stage test = new Stage();
                test.setId("test-" + x);
                test.setName("Write Tests");
                test.setNodeType("agent");
                test.setDependencies(s.getDependencies() != null
                        ? new ArrayList<>(s.getDependencies()) : null);
                test.setSystemPrompt("Write tests for the planned implementation. "
                        + "Cover edge cases, normal operation, and expected failures.\n"
                        + (s.getSystemPrompt() != null ? s.getSystemPrompt() : ""));
                test.setModel(s.getModel());
                test.setConfig(s.getConfig() != null ? new HashMap<>(s.getConfig()) : null);
                test.setPositionX(s.getPositionX());
                test.setPositionY(s.getPositionY());

                // verify-test-X — depends on test-X, receives test output as context
                Stage verifyTest = new Stage();
                verifyTest.setId("verify-test-" + x);
                verifyTest.setName("Verify Tests");
                verifyTest.setNodeType("verifier");
                verifyTest.setDependencies(List.of(test.getId()));
                verifyTest.setSystemPrompt("Verify that the tests are correctly written, "
                        + "are executable, and cover the planned functionality. "
                        + "Run them to confirm the test harness works.\n"
                        + "Tests written by upstream stage: {{upstreamOutput}}");
                verifyTest.setModel(s.getModel());
                verifyTest.setInputMapping(new HashMap<>(Map.of(
                        test.getId(), "upstreamOutput"
                )));
                verifyTest.setPositionX(s.getPositionX() + 200);
                verifyTest.setPositionY(s.getPositionY());

                // impl-X — replaces original agent, depends on test-X only (not verify-test-X)
                // Receives test output so implementation satisfies the written tests
                Stage impl = new Stage();
                impl.setId("impl-" + x);
                impl.setName("Implement");
                impl.setNodeType("agent");
                impl.setDependencies(List.of(test.getId()));
                impl.setSystemPrompt("Implement the planned functionality. "
                        + "Write code that passes the tests written in the previous stage.\n"
                        + (s.getSystemPrompt() != null ? s.getSystemPrompt() : "")
                        + "\nTests to satisfy: {{upstreamOutput}}");
                impl.setModel(s.getModel());
                impl.setConfig(s.getConfig() != null ? new HashMap<>(s.getConfig()) : null);
                impl.setInputMapping(new HashMap<>(Map.of(
                        test.getId(), "upstreamOutput"
                )));
                impl.setPositionX(s.getPositionX() + 400);
                impl.setPositionY(s.getPositionY());

                // verify-X — replaces original verifier, depends on impl-X
                // Receives impl output so it knows what implementation was written
                Stage verify = new Stage();
                verify.setId("verify-" + x);
                verify.setName("Verify Implementation");
                verify.setNodeType("verifier");
                verify.setDependencies(List.of(impl.getId()));
                verify.setSystemPrompt("Verify the implementation against the requirements. "
                        + "Run the tests to confirm everything passes.\n"
                        + (verifier.getSystemPrompt() != null ? verifier.getSystemPrompt() : "")
                        + "\nImplementation to verify: {{upstreamOutput}}");
                verify.setModel(verifier.getModel());
                verify.setInputMapping(new HashMap<>(Map.of(
                        impl.getId(), "upstreamOutput"
                )));
                verify.setPositionX(s.getPositionX() + 600);
                verify.setPositionY(s.getPositionY());

                expanded.add(test);
                expanded.add(verifyTest);
                expanded.add(impl);
                expanded.add(verify);

                // Rewrite downstream dependency references: old IDs → new
                depRewrites.put(s.getId(), impl.getId());       // old agent → new impl
                depRewrites.put(verifier.getId(), verify.getId()); // old verifier → new verify

                skipIds.add(verifier.getId());
            } else {
                expanded.add(s);
            }
        }

        // Fix up any downstream stages that referenced the replaced stage IDs
        for (Stage stage : expanded) {
            if (stage.getDependencies() != null) {
                List<String> updated = new ArrayList<>();
                for (String dep : stage.getDependencies()) {
                    updated.add(depRewrites.getOrDefault(dep, dep));
                }
                stage.setDependencies(updated);
            }
        }

        pipeline.setStages(expanded);
        log.info("expandTddStages: expanded {} stages to {} stages (tddEnabled=true)",
                stages.size(), expanded.size());
    }

    /**
     * Finds the first verifier stage whose dependencies include the given agent stage's ID.
     * Returns null if the given stage is not an agent or no matching verifier is found.
     */
    private static Stage findVerifierForAgent(List<Stage> stages, Stage agent) {
        if (!"agent".equals(agent.getNodeType())) return null;
        for (Stage s : stages) {
            if ("verifier".equals(s.getNodeType())
                    && s.getDependencies() != null
                    && s.getDependencies().contains(agent.getId())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Initialize the stage status map for an ExecutionRun.
     */
    static void initializeRunStageStatus(ExecutionRun run, List<Stage> stages) {
        Map<String, String> initStatus = new HashMap<>();
        for (Stage s : stages) {
            initStatus.put(s.getId(), "pending");
        }
        run.setStageStatus(initStatus);
    }

    /**
     * Create Stage objects from the schema's canvas nodes/edges.
     * Each canvas node becomes a Stage with the same ID, name, model, systemPrompt, nodeType, and config.
     * Dependencies are derived from edges (source → target).
     */
    static List<Stage> createStagesFromNodes(WorkflowSchema schema) {
        List<Stage> stages = new ArrayList<>();
        if (schema.getNodes() == null || schema.getNodes().isEmpty()) return stages;

        // Build adjacency: nodeId → list of source node IDs (dependencies)
        Map<String, List<String>> depMap = new HashMap<>();
        if (schema.getEdges() != null) {
            for (Edge edge : schema.getEdges()) {
                if (edge.getSource() != null && edge.getTarget() != null) {
                    depMap.computeIfAbsent(edge.getTarget(), k -> new ArrayList<>()).add(edge.getSource());
                }
            }
        }

        for (Node node : schema.getNodes()) {
            // Guard: null node
            if (node == null) {
                log.warn("Null node in schema, skipping");
                continue;
            }

            // Guard: null node id
            String nodeId = node.getId();
            if (nodeId == null) {
                log.warn("Node has null id, skipping");
                continue;
            }
            String shortId = nodeId.length() >= 8 ? nodeId.substring(0, 8) : nodeId;

            // Guard: null data
            var data = node.getData();
            if (data == null) {
                log.warn("Node {} has no data, skipping", shortId);
                continue;
            }

            Stage stage = new Stage();
            stage.setId(nodeId);
            stage.setName(node.getName() != null ? node.getName() : node.getType() + "-" + shortId);
            stage.setNodeType(node.getType());

            // Copy model from node data if set
            if (data.getModel() != null) {
                stage.setModel(data.getModel());
            }

            // Copy systemPrompt from node data
            if (data.getSystemPrompt() != null) {
                stage.setSystemPrompt(data.getSystemPrompt());
            }

            // Copy config into stage config
            if (data.getConfig() != null) {
                stage.setConfig(new HashMap<>(data.getConfig()));
            }

            // Ensure agent nodes have default tools
            if ("agent".equals(node.getType())) {
                Map<String, Object> cfg = stage.getConfig();
                if (cfg == null) {
                    cfg = new HashMap<>();
                    stage.setConfig(cfg);
                }
                if (cfg.get("tools") == null && cfg.get("enabledTools") == null) {
                    cfg.put("tools", List.of("file_read", "file_write", "bash", "grep", "directory_read"));
                }
            }

            // Set dependencies from edge adjacency
            List<String> deps = depMap.getOrDefault(nodeId, List.of());
            if (!deps.isEmpty()) {
                stage.setDependencies(new ArrayList<>(deps));
            }

            stages.add(stage);
        }

        return stages;
    }
}
