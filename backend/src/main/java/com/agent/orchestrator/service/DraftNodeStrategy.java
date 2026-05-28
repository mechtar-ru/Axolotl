package com.agent.orchestrator.service;

import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.DraftResult;
import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Strategy for executing draft nodes.
 * Produces structured artifacts (spec, plan, ui spec, backend modules)
 * via a single LLM call each — no tools, fast and cheap.
 *
 * Draft types:
 *   spec     → targetPath/.axolotl/spec.md      — application specification
 *   plan     → targetPath/.axolotl/plan.md      — implementation plan from spec
 *   ui       → targetPath/.axolotl/openui.yaml  — OpenUI YAML spec
 *   backend  → targetPath/.axolotl/modules.md   — backend module architecture
 */
@Component
public class DraftNodeStrategy {

    private static final Logger log = LoggerFactory.getLogger(DraftNodeStrategy.class);

    private final ExecutionUtilityService utilityService;
    private final LlmService llmService;
    private final ExecutionWebSocketHandler webSocketHandler;
    private final Neo4jSchemaRepository schemaRepository;

    private static final String AXOLOTL_DIR = ".axolotl";

    // ── System prompts per draft type ──

    private static final String SPEC_SYSTEM_PROMPT = """
            You are a product spec writer. Given a user's application description, produce a clear,
            structured specification document in markdown. Cover:
            - Purpose & audience
            - Core features (must have vs nice to have)
            - User flows
            - Data model sketch
            - Key UI screens/routes
            - Constraints and non-goals
            Be concise (2-3 paragraphs per section) and actionable.
            """;

    private static final String PLAN_SYSTEM_PROMPT = """
            You are a senior engineer planning implementation. Given a spec document (or user description),
            produce a phased implementation plan in markdown. Cover:
            - Phase breakdown with clear dependencies
            - Which files/modules to create first
            - Technical decisions and rationale
            - Testing strategy per phase
            Be concrete — reference actual file paths, function names, and library choices.
            """;

    private static final String UI_SYSTEM_PROMPT = """
            You are a UI designer. Given an application spec/description, produce an OpenUI-compatible
            YAML specification describing the app's UI component hierarchy.
            Use the OpenUI format:
            ```yaml
            - name: ComponentName
              template: |
                <div class="...">
                  ...
                </div>
              parameters:
                title: string
                ...
            ```
            Cover: layout structure, all screens/views, reusable components,
            navigation patterns, responsive breakpoints.
            Focus on the component tree — concrete HTML/CSS structure.
            """;

    private static final String BACKEND_SYSTEM_PROMPT = """
            You are a backend architect. Given an application spec, design the backend module structure
            in markdown. Cover:
            - Module/directory layout
            - API endpoints (method, path, purpose)
            - Data models and relationships
            - Service layer design
            - Database schema sketch
            - Authentication and authorization boundaries
            Be concrete with file paths, class names, and field types.
            """;

    // ── File names per draft type ──

    private static final Map<String, String> DRAFT_FILES = new HashMap<>();
    static {
        DRAFT_FILES.put("spec", "spec.md");
        DRAFT_FILES.put("plan", "plan.md");
        DRAFT_FILES.put("ui", "openui.yaml");
        DRAFT_FILES.put("backend", "modules.md");
    }

    private static final Map<String, String> DRAFT_PROMPTS = new HashMap<>();
    static {
        DRAFT_PROMPTS.put("spec", SPEC_SYSTEM_PROMPT);
        DRAFT_PROMPTS.put("plan", PLAN_SYSTEM_PROMPT);
        DRAFT_PROMPTS.put("ui", UI_SYSTEM_PROMPT);
        DRAFT_PROMPTS.put("backend", BACKEND_SYSTEM_PROMPT);
    }

    public DraftNodeStrategy(ExecutionUtilityService utilityService,
                             LlmService llmService,
                             ExecutionWebSocketHandler webSocketHandler,
                             Neo4jSchemaRepository schemaRepository) {
        this.utilityService = utilityService;
        this.llmService = llmService;
        this.webSocketHandler = webSocketHandler;
        this.schemaRepository = schemaRepository;
    }

    /**
     * Execute a draft node — produce the appropriate artifact.
     */
    public String executeDraftNode(Node node, String schemaId, String resolvedModel) {
        Map<String, Object> config = node.getData() != null ? node.getData().getConfig() : new HashMap<>();
        String draftType = config != null ? (String) config.get("draftType") : null;
        if (draftType == null || draftType.isBlank()) {
            draftType = "spec";
        }

        String fileName = DRAFT_FILES.getOrDefault(draftType, "draft.md");
        String systemPrompt = DRAFT_PROMPTS.getOrDefault(draftType, SPEC_SYSTEM_PROMPT);

        // Determine target path
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        String targetPath = schema != null && schema.getTargetPath() != null
                ? schema.getTargetPath() : ".";
        Path draftsDir = Paths.get(targetPath, AXOLOTL_DIR);

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20,
                    "Generating " + draftType + " draft");
            webSocketHandler.sendLog(schemaId, "info",
                    "Draft node: generating " + draftType + " draft", node.getId());
        }

        // Collect predecessor context as input
        var predResults = utilityService.collectPredecessorResults(
                schemaRepository.findById(schemaId), node.getId());
        String input = predResults.values().stream()
                .findFirst().map(Object::toString).orElse("");
        if (input.isBlank()) {
            input = node.getData() != null && node.getData().getSourceData() != null
                    ? node.getData().getSourceData() : "No input provided";
        }

        // Resolve model
        String model = resolvedModel;
        if (model == null) {
            model = utilityService.resolveModel(
                    node.getData() != null ? node.getData().getModel() : null,
                    null, null, null);
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 50, "Calling LLM");
        }

        // Single LLM call — no tools
        String llmResponse = llmService.chat(model, systemPrompt, input, null);
        if (llmResponse == null || llmResponse.isBlank()
                || llmResponse.startsWith("Error:")) {
            String error = "Draft failed: " + (llmResponse != null ? llmResponse : "empty response");
            log.warn(error);
            return error;
        }

        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 75, "Writing artifact");
        }

        // Write artifact file
        try {
            Files.createDirectories(draftsDir);
            Path artifactPath = draftsDir.resolve(fileName);
            Files.writeString(artifactPath, llmResponse);

            String summary = draftType + " draft written to " + artifactPath.toString()
                    + " (" + llmResponse.length() + " chars)";

            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "success", summary, node.getId());
                webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 100, "Draft complete");
            }

            // Return DraftResult as JSON for downstream node consumption
            DraftResult result = new DraftResult(draftType, artifactPath.toString(), summary);
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("Failed to write draft artifact: {}", e.getMessage(), e);
            return "Error writing draft: " + e.getMessage();
        }
    }
}
