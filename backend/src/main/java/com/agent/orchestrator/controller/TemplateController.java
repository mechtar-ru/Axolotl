package com.agent.orchestrator.controller;

import com.agent.orchestrator.service.SchemaService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    @GetMapping
    public List<Map<String, Object>> getTemplates() {
        return List.of(projectPlanningTemplate(), sokobanGameTemplate());
    }

    @GetMapping("/{id}")
    public Map<String, Object> getTemplate(@PathVariable String id) {
        if ("project-planning".equals(id)) return projectPlanningTemplate();
        if ("sokoban-game".equals(id)) return sokobanGameTemplate();
        throw new NoSuchElementException("Template not found: " + id);
    }

    private Map<String, Object> projectPlanningTemplate() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", "project-planning");
        t.put("name", "Project Planning Pipeline");
        t.put("description", "Analyze project structure → design features → break down into tasks → generate implementation schema");
        t.put("icon", "🏗️");

        // Nodes
        List<Map<String, Object>> nodes = new ArrayList<>();

        // Source: project context
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", "s1");
        source.put("type", "source");
        source.put("name", "Project Context");
        source.put("position", Map.of("x", 100, "y", 50));
        Map<String, Object> sourceData = new LinkedHashMap<>();
        sourceData.put("config", Map.of(
                "sourceType", "project",
                "maxDepth", 4,
                "maxFiles", 50
        ));
        source.put("data", sourceData);
        nodes.add(source);

        // Agent: Architecture Analyst
        Map<String, Object> archAgent = new LinkedHashMap<>();
        archAgent.put("id", "a1");
        archAgent.put("type", "agent");
        archAgent.put("name", "Architecture Analyst");
        archAgent.put("position", Map.of("x", 100, "y", 250));
        Map<String, Object> archData = new LinkedHashMap<>();
        archData.put("systemPrompt", SchemaService.ARCHITECT_ANALYST_PROMPT);
        archData.put("userPrompt", "Analyze the project structure provided above. Identify architecture patterns, technology stack, modules, extension points, and constraints.");
        archData.put("model", "");
        archAgent.put("data", archData);
        nodes.add(archAgent);

        // Agent: Feature Designer
        Map<String, Object> featAgent = new LinkedHashMap<>();
        featAgent.put("id", "a2");
        featAgent.put("type", "agent");
        featAgent.put("name", "Feature Designer");
        featAgent.put("position", Map.of("x", 100, "y", 450));
        Map<String, Object> featData = new LinkedHashMap<>();
        featData.put("systemPrompt", SchemaService.FEATURE_DESIGNER_PROMPT);
        featData.put("userPrompt", "Based on the architecture analysis above, design the implementation approach for these features:\n\n{{features}}");
        featData.put("model", "");
        featAgent.put("data", featData);
        nodes.add(featAgent);

        // Agent: Task Breakdown
        Map<String, Object> taskAgent = new LinkedHashMap<>();
        taskAgent.put("id", "a3");
        taskAgent.put("type", "agent");
        taskAgent.put("name", "Task Breakdown");
        taskAgent.put("position", Map.of("x", 100, "y", 650));
        Map<String, Object> taskData = new LinkedHashMap<>();
        taskData.put("systemPrompt", SchemaService.TASK_BREAKDOWN_PROMPT);
        taskData.put("userPrompt", "Break down the feature designs above into a concrete task list. Output as JSON.");
        taskData.put("model", "");
        taskAgent.put("data", taskData);
        nodes.add(taskAgent);

        // SchemaBuilder
        Map<String, Object> schemaBuilder = new LinkedHashMap<>();
        schemaBuilder.put("id", "sb1");
        schemaBuilder.put("type", "schemabuilder");
        schemaBuilder.put("name", "Generate Implementation Schema");
        schemaBuilder.put("position", Map.of("x", 100, "y", 850));
        Map<String, Object> sbData = new LinkedHashMap<>();
        sbData.put("model", "");
        sbData.put("config", Map.of("generateMd", true));
        schemaBuilder.put("data", sbData);
        nodes.add(schemaBuilder);

        t.put("nodes", nodes);

        // Edges
        List<Map<String, String>> edges = new ArrayList<>();
        edges.add(Map.of("id", "e1", "source", "s1", "target", "a1"));
        edges.add(Map.of("id", "e2", "source", "a1", "target", "a2"));
        edges.add(Map.of("id", "e3", "source", "a2", "target", "a3"));
        edges.add(Map.of("id", "e4", "source", "a3", "target", "sb1"));
        t.put("edges", edges);

        // Variables users should fill in
        t.put("variables", List.of(
                Map.of("name", "projectPath", "description", "Path to the project directory", "required", true, "nodeId", "s1", "field", "config.projectPath"),
                Map.of("name", "features", "description", "List of features to plan (markdown)", "required", true, "nodeId", "a2", "field", "userPrompt")
        ));

        return t;
    }

    private Map<String, Object> sokobanGameTemplate() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", "sokoban-game");
        t.put("name", "Sokoban Game Generator");
        t.put("description", "Generate a playable Sokoban puzzle game from grid parameters and level design");
        t.put("icon", "🎮");

        // Nodes
        List<Map<String, Object>> nodes = new ArrayList<>();

        // Source: game parameters
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", "s1");
        source.put("type", "source");
        source.put("name", "Game Parameters");
        source.put("position", Map.of("x", 100, "y", 50));
        Map<String, Object> sourceData = new LinkedHashMap<>();
        sourceData.put("config", Map.of(
            "sourceType", "text",
            "grid", "8x8",
            "level", "Classic Sokoban level 1"
        ));
        source.put("data", sourceData);
        nodes.add(source);

        // Agent: Game Generator
        Map<String, Object> gameAgent = new LinkedHashMap<>();
        gameAgent.put("id", "a1");
        gameAgent.put("type", "agent");
        gameAgent.put("name", "Game Generator");
        gameAgent.put("position", Map.of("x", 100, "y", 250));
        Map<String, Object> agentData = new LinkedHashMap<>();
        agentData.put("systemPrompt",
            "You are a game developer specializing in generating playable HTML/JS games. " +
            "Given grid parameters and level designs, you must output a COMPLETE, self-contained HTML file " +
            "that includes all CSS and JavaScript inline. The game must be immediately playable in a browser. " +
            "Include: grid rendering, player movement (arrow keys), collision detection, win condition, " +
            "move counter, undo functionality, and visual feedback.");
        agentData.put("userPrompt",
            "Generate a playable Sokoban game with these specifications:\n" +
            "- Grid dimensions: {{grid}}\n" +
            "- Level design: {{level}}\n\n" +
            "Output only the complete HTML file content.");
        agentData.put("model", "");
        gameAgent.put("data", agentData);
        nodes.add(gameAgent);

        // Output: generated game
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", "o1");
        output.put("type", "output");
        output.put("name", "Generated Game");
        output.put("position", Map.of("x", 100, "y", 450));
        output.put("data", Map.of("outputType", "log"));
        nodes.add(output);

        t.put("nodes", nodes);

        // Edges
        List<Map<String, String>> edges = new ArrayList<>();
        edges.add(Map.of("id", "e1", "source", "s1", "target", "a1"));
        edges.add(Map.of("id", "e2", "source", "a1", "target", "o1"));
        t.put("edges", edges);

        // Variables users should fill in
        t.put("variables", List.of(
            Map.of("name", "grid", "description", "Grid dimensions (e.g. 8x8)", "required", true, "nodeId", "s1", "field", "config.grid"),
            Map.of("name", "level", "description", "Level layout description or JSON", "required", true, "nodeId", "a1", "field", "userPrompt")
        ));

        return t;
    }
}
