package com.agent.orchestrator.analytics;

import com.agent.orchestrator.model.WorkflowSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.*;

/**
 * Service for managing workflow templates with file-based storage.
 * 
 * Templates are stored as JSON files in the templates/ directory.
 */
public class WorkflowTemplateService {
    private final String templatesDir;
    private final ObjectMapper objectMapper;

    public WorkflowTemplateService() {
        this("templates");
    }

    public WorkflowTemplateService(String templatesDir) {
        this.templatesDir = templatesDir;
        this.objectMapper = new ObjectMapper();
        new File(templatesDir).mkdirs();
    }

    /**
     * Saves a workflow as a template.
     */
    public WorkflowTemplate saveAsTemplate(WorkflowSchema workflow, String name, String description) {
        WorkflowTemplate template = new WorkflowTemplate();
        template.setName(name);
        template.setDescription(description);
        template.setWorkflow(workflow);
        template.setUpdatedAt(new Date());

        try {
            String filename = templatesDir + "/" + template.getId() + ".json";
            objectMapper.writeValue(new File(filename), template);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save template", e);
        }

        return template;
    }

    /**
     * Loads a template by ID.
     */
    public WorkflowTemplate loadTemplate(String templateId) {
        try {
            String filename = templatesDir + "/" + templateId + ".json";
            return objectMapper.readValue(new File(filename), WorkflowTemplate.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load template: " + templateId, e);
        }
    }

    /**
     * Creates a new workflow from a template.
     */
    public WorkflowSchema createFromTemplate(String templateId) {
        WorkflowTemplate template = loadTemplate(templateId);
        // Return a copy of the workflow with a new ID
        try {
            String json = objectMapper.writeValueAsString(template.getWorkflow());
            WorkflowSchema workflow = objectMapper.readValue(json, WorkflowSchema.class);
            workflow.setId(UUID.randomUUID().toString()); // New ID for the instance
            return workflow;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create workflow from template", e);
        }
    }

    /**
     * Lists all available templates.
     */
    public List<WorkflowTemplate> listTemplates() {
        List<WorkflowTemplate> templates = new ArrayList<>();
        File dir = new File(templatesDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));

        if (files != null) {
            for (File file : files) {
                try {
                    templates.add(objectMapper.readValue(file, WorkflowTemplate.class));
                } catch (Exception e) {
                    // Skip invalid template files
                }
            }
        }

        return templates;
    }

    /**
     * Deletes a template.
     */
    public void deleteTemplate(String templateId) {
        String filename = templatesDir + "/" + templateId + ".json";
        new File(filename).delete();
    }
}
