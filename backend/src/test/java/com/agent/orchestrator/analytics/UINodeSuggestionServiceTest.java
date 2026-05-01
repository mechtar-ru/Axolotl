package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for UINodeSuggestionService.
 */
class UINodeSuggestionServiceTest {
    private PatternDetector patternDetector;
    private NodeFactory nodeFactory;
    private WorkflowTemplateService templateService;
    private UINodeSuggestionService suggestionService;

    @BeforeEach
    void setUp() {
        patternDetector = new PatternDetector(2, 3);
        nodeFactory = new NodeFactory();
        templateService = new WorkflowTemplateService("test-templates-suggestions");
        suggestionService = new UINodeSuggestionService(patternDetector, nodeFactory, templateService);
    }

    @Test
    void shouldSuggestOutputNode() {
        WorkflowSchema workflow = new WorkflowSchema();
        Node source = new Node();
        source.setType("source");
        Node agent = new Node();
        agent.setType("agent");
        workflow.setNodes(Arrays.asList(source, agent));

        List<UINodeSuggestionService.Suggestion> suggestions = suggestionService.getSuggestions(workflow);

        assertTrue(suggestions.stream()
            .anyMatch(s -> "output".equals(s.getId()) || s.getReason().contains("output")));
    }

    @Test
    void shouldSuggestGeneratedNodes() {
        PatternDetector.Pattern pattern = new PatternDetector.Pattern(
            Arrays.asList("source", "agent")
        );
        nodeFactory.generateNode(pattern);

        WorkflowSchema workflow = new WorkflowSchema();
        List<UINodeSuggestionService.Suggestion> suggestions = suggestionService.getSuggestions(workflow);

        assertTrue(suggestions.stream()
            .anyMatch(s -> "generated".equals(s.getType())));
    }

    @Test
    void shouldGetTemplateSuggestions() {
        WorkflowSchema workflow = new WorkflowSchema();
        templateService.saveAsTemplate(workflow, "Test Template", "");

        List<WorkflowTemplate> templates = suggestionService.getTemplateSuggestions("");

        assertFalse(templates.isEmpty());
    }

    @Test
    void shouldTrackFeedback() {
        suggestionService.recordFeedback("test-suggestion", true);
        suggestionService.recordFeedback("test-suggestion", true);

        double rate = suggestionService.getAcceptanceRate("test-suggestion");
        assertTrue(rate > 0.5);
    }
}
