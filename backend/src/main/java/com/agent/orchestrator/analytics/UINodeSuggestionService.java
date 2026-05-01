package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.*;
import java.util.*;

/**
 * Backend service for node suggestions in UI.
 * 
 * Integration: UI calls this service to get suggestions based on:
 * - Current workflow context (nodes, connections)
 * - Historical patterns from PatternDetector
 * - Generated nodes from NodeFactory
 * - Templates from WorkflowTemplateService
 */


public class UINodeSuggestionService {
    private final PatternDetector patternDetector;
    private final NodeFactory nodeFactory;
    private final WorkflowTemplateService templateService;
    private final Map<String, Integer> suggestionFeedback = new HashMap<>();

    public UINodeSuggestionService(PatternDetector patternDetector, 
                                   NodeFactory nodeFactory,
                                   WorkflowTemplateService templateService) {
        this.patternDetector = patternDetector;
        this.nodeFactory = nodeFactory;
        this.templateService = templateService;
    }

    /**
     * Gets node suggestions based on current workflow context.
     */
    public List<Suggestion> getSuggestions(WorkflowSchema currentWorkflow) {
        List<Suggestion> suggestions = new ArrayList<>();

        // Simple heuristic: if workflow has source+agent, suggest output
        if (currentWorkflow.getNodes() != null) {
            boolean hasSource = currentWorkflow.getNodes().stream()
                .anyMatch(n -> "source".equals(n.getType()));
            boolean hasAgent = currentWorkflow.getNodes().stream()
                .anyMatch(n -> "agent".equals(n.getType()));
            
            if (hasSource && hasAgent) {
                suggestions.add(new Suggestion("output", "node", 
                    "Add output node (source+agent detected)"));
            }
        }

        // Suggest generated nodes from NodeFactory (always suggest these)
        for (Map.Entry<String, PatternDetector.Pattern> entry : nodeFactory.getAllGeneratedNodes().entrySet()) {
            suggestions.add(new Suggestion(entry.getKey(), "generated",
                "Generated node: " + String.join("-", entry.getValue().getSequence())));
        }

        return suggestions;
    }

    /**
     * Gets template suggestions.
     */
    public List<WorkflowTemplate> getTemplateSuggestions(String context) {
        return templateService.listTemplates();
    }

    /**
     * Records user feedback on a suggestion.
     */
    public void recordFeedback(String suggestionId, boolean accepted) {
        suggestionFeedback.merge(suggestionId, accepted ? 1 : -1, Integer::sum);
    }

    /**
     * Gets suggestion acceptance rate.
     */
    public double getAcceptanceRate(String suggestionId) {
        Integer score = suggestionFeedback.get(suggestionId);
        return score == null ? 0.5 : Math.max(0, Math.min(1, 0.5 + score * 0.1));
    }

    /**
     * Represents a suggestion.
     */
    public static class Suggestion {
        private final String id;
        private final String type;
        private final String reason;

        public Suggestion(String id, String type, String reason) {
            this.id = id;
            this.type = type;
            this.reason = reason;
        }

        public String getId() { return id; }
        public String getType() { return type; }
        public String getReason() { return reason; }
    }
}
