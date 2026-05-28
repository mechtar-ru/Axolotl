package com.agent.orchestrator.service;

import com.agent.orchestrator.llm.LlmService;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.graph.repository.Neo4jSchemaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

    private static final String OUTLINE_SYSTEM_PROMPT = """
            You are a planning assistant. Based on the user's prompt, create a high-level plan and ask clarifying questions.

            Respond ONLY with valid JSON (no markdown fences):
            {
              "plan": "markdown outline of the plan (3-7 bullet points)",
              "questions": [
                {
                  "id": "q1",
                  "text": "Clear question text?",
                  "defaultAnswer": "reasonable default",
                  "options": ["option1", "option2", "option3"]
                }
              ]
            }

            Include 2-4 questions that help refine the direction. Questions can have options for pick-lists or just a defaultAnswer for free text.
            """;

    private static final String REFINE_SYSTEM_PROMPT = """
            You are a design document writer. The user has:
            - An original prompt
            - A high-level outline (from a previous step)
            - Their own edits and clarifications
            - Answers to clarifying questions

            Create a detailed design document that expands the outline into a complete specification.
            Be specific, include architecture decisions, component descriptions, data flow, and implementation notes.

            Respond ONLY with valid JSON (no markdown fences):
            {
              "plan": "Complete markdown design document with sections for Overview, Architecture, Components, Data Flow, Implementation Notes"
            }
            """;

    private final LlmService llmService;
    private final Neo4jSchemaRepository schemaRepository;
    private final ObjectMapper objectMapper;

    public PlanningService(LlmService llmService, Neo4jSchemaRepository schemaRepository) {
        this.llmService = llmService;
        this.schemaRepository = schemaRepository;
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, Object> generateOutline(String schemaId, String prompt, String model) {
        Map<String, Object> result = new HashMap<>();
        try {
            String resolvedModel = resolveOutlineModel(schemaId, model);
            log.info("Planning outline for schema {} using model {}", schemaId, resolvedModel);
            String llmResponse = llmService.chat(resolvedModel, OUTLINE_SYSTEM_PROMPT, prompt, null).text();
            if (llmResponse == null || llmResponse.isBlank()) {
                result.put("success", false);
                result.put("error", "LLM returned empty response");
                return result;
            }
            return parseLlmResponse(llmResponse, "outline");
        } catch (Exception e) {
            log.error("Outline generation failed for schema {}: {}", schemaId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    public Map<String, Object> refinePlan(String schemaId, String prompt, String model,
                                          String outline, String userEdits, Map<String, String> answers) {
        Map<String, Object> result = new HashMap<>();
        try {
            String resolvedModel = resolveRefineModel(schemaId, model);
            String userPrompt = buildRefineUserPrompt(prompt, outline, userEdits, answers);
            log.info("Refining plan for schema {} using model {}", schemaId, resolvedModel);
            String llmResponse = llmService.chat(resolvedModel, REFINE_SYSTEM_PROMPT, userPrompt, null).text();
            if (llmResponse == null || llmResponse.isBlank()) {
                result.put("success", false);
                result.put("error", "LLM returned empty response");
                return result;
            }
            return parseLlmResponse(llmResponse, "refine");
        } catch (Exception e) {
            log.error("Plan refinement failed for schema {}: {}", schemaId, e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private String resolveOutlineModel(String schemaId, String userModel) {
        if (userModel != null && !userModel.isBlank()) return userModel;
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema != null && schema.getPlanningModels() != null) {
            String fast = schema.getPlanningModels().get("fast");
            if (fast != null && !fast.isBlank()) return fast;
        }
        if (schema != null && schema.getDefaultModel() != null && !schema.getDefaultModel().isBlank()) {
            return schema.getDefaultModel();
        }
        return "gpt-4o-mini";
    }

    private String resolveRefineModel(String schemaId, String userModel) {
        if (userModel != null && !userModel.isBlank()) return userModel;
        WorkflowSchema schema = schemaRepository.findById(schemaId);
        if (schema != null && schema.getPlanningModels() != null) {
            String medium = schema.getPlanningModels().get("medium");
            if (medium != null && !medium.isBlank()) return medium;
        }
        if (schema != null && schema.getDefaultModel() != null && !schema.getDefaultModel().isBlank()) {
            return schema.getDefaultModel();
        }
        return "deepseek-chat";
    }

    private String buildRefineUserPrompt(String originalPrompt, String outline,
                                          String userEdits, Map<String, String> answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Original Prompt\n").append(originalPrompt).append("\n\n");
        if (outline != null && !outline.isBlank()) {
            sb.append("## Current Outline\n").append(outline).append("\n\n");
        }
        if (userEdits != null && !userEdits.isBlank()) {
            sb.append("## User Edits / Feedback\n").append(userEdits).append("\n\n");
        }
        if (answers != null && !answers.isEmpty()) {
            sb.append("## Answers to Questions\n");
            for (Map.Entry<String, String> e : answers.entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("Please create a detailed design document based on all of the above.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseLlmResponse(String raw, String type) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", type);
        try {
            String jsonStr = raw.trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceFirst("^```\\w*\\n?", "").replaceFirst("\\n?```$", "");
            }
            Map<String, Object> parsed = objectMapper.readValue(jsonStr, Map.class);
            String planContent = parsed.containsKey("plan") ? (String) parsed.get("plan") : jsonStr;
            result.put("content", planContent);
            result.put("success", true);
            if ("outline".equals(type) && parsed.containsKey("questions")) {
                result.put("questions", parsed.get("questions"));
            }
            if ("refine".equals(type)) {
                result.put("content", planContent);
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON, using raw text: {}", e.getMessage());
            result.put("success", true);
            result.put("content", raw);
            return result;
        }
    }
}
