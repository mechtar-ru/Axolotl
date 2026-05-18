package com.agent.orchestrator.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private static final Logger log = LoggerFactory.getLogger(HarnessController.class);
    private static final Path HARNESS_BASE = Paths.get("/Users/evgenijtihomirov/git/Axolotl/Axolotl", "harness");

    @GetMapping("/components")
    public ResponseEntity<Map<String, Object>> getComponents() {
        Map<String, Object> response = new LinkedHashMap<>();
        
        List<Map<String, Object>> components = new ArrayList<>();
        
        components.add(component("system_prompt", "System Prompt", "Agent instructions", "system_prompt.txt"));
        components.add(component("tool_specs", "Tool Specifications", "Tool definitions", "tool_specs/"));
        components.add(component("skills", "Skills", "Prompt engineering skills", "skills/"));
        components.add(component("sub_agents", "Sub-agents", "Nested agent configs", "sub_agents/"));
        components.add(component("memory", "Memory", "Long-term memory config", "memory/"));
        components.add(component("middleware", "Middleware", "Context compaction, fallback", "middleware/"));
        
        response.put("components", components);
        response.put("count", components.size());
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/components/{componentId}")
    public ResponseEntity<Map<String, Object>> getComponent(@PathVariable String componentId) {
        Path componentPath = HARNESS_BASE.resolve(componentId);
        
        try {
            if (!Files.exists(componentPath)) {
                return ResponseEntity.notFound().build();
            }
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", componentId);
            response.put("path", "harness/" + componentId);
            
            if (Files.isRegularFile(componentPath)) {
                response.put("content", Files.readString(componentPath));
            } else if (Files.isDirectory(componentPath)) {
                List<String> files = new ArrayList<>();
                try (var stream = Files.list(componentPath)) {
                    stream.forEach(p -> files.add(p.getFileName().toString()));
                }
                response.put("files", files);
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error reading component: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/components/{componentId}")
    public ResponseEntity<Map<String, Object>> updateComponent(
            @PathVariable String componentId,
            @RequestBody Map<String, String> body) {
        
        Path componentPath = HARNESS_BASE.resolve(componentId);
        
        try {
            Files.createDirectories(componentPath.getParent());
            Files.writeString(componentPath, body.get("content"));
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", componentId);
            response.put("status", "saved");
            response.put("path", "harness/" + componentId);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving component: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

private Map<String, Object> component(String id, String name, String description, String path) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.put("description", description);
        c.put("path", path);
        return c;
    }
}