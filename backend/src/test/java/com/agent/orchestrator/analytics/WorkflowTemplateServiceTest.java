package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.io.File;

/**
 * Unit tests for WorkflowTemplateService.
 */
class WorkflowTemplateServiceTest {
    private WorkflowTemplateService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTemplateService("test-templates");
        // Clean up any existing test templates
        File dir = new File("test-templates");
        if (dir.exists()) {
            for (File f : dir.listFiles()) f.delete();
        }
    }

    @Test
    void shouldSaveAndLoadTemplate() {
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setId("test-workflow");
        
        WorkflowTemplate template = service.saveAsTemplate(workflow, "Test Template", "A test template");
        
        assertNotNull(template.getId());
        assertEquals("Test Template", template.getName());
        
        // Load it back
        WorkflowTemplate loaded = service.loadTemplate(template.getId());
        assertEquals(template.getId(), loaded.getId());
        assertEquals("Test Template", loaded.getName());
    }

    @Test
    void shouldCreateWorkflowFromTemplate() {
        WorkflowSchema workflow = new WorkflowSchema();
        workflow.setId("original");
        Node node = new Node();
        node.setId("node-1");
        workflow.setNodes(List.of(node));
        
        WorkflowTemplate template = service.saveAsTemplate(workflow, "Source Template", "");
        
        WorkflowSchema created = service.createFromTemplate(template.getId());
        assertNotNull(created);
        assertNotEquals("original", created.getId()); // Should be a copy
        assertEquals(1, created.getNodes().size());
    }

    @Test
    void shouldListTemplates() {
        WorkflowSchema workflow = new WorkflowSchema();
        service.saveAsTemplate(workflow, "Template 1", "");
        service.saveAsTemplate(workflow, "Template 2", "");
        
        List<WorkflowTemplate> templates = service.listTemplates();
        assertEquals(2, templates.size());
    }

    @Test
    void shouldDeleteTemplate() {
        WorkflowSchema workflow = new WorkflowSchema();
        WorkflowTemplate template = service.saveAsTemplate(workflow, "To Delete", "");
        
        service.deleteTemplate(template.getId());
        
        List<WorkflowTemplate> templates = service.listTemplates();
        assertTrue(templates.isEmpty());
    }
}
