package com.agent.orchestrator.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/harness")
public class EvolveController {

    private static final Logger log = LoggerFactory.getLogger(EvolveController.class);
    private static final Path HARNESS_DIR = Paths.get("/Users/evgenijtihomirov/git/Axolotl/Axolotl/harness");
    private static final Path EVIDENCE_DIR = Paths.get("/Users/evgenijtihomirov/git/Axolotl/Axolotl/harness/evidence");

    @PostMapping("/evolve")
    public ResponseEntity<Map<String, Object>> evolve(@RequestBody Map<String, Object> params) {
        String schemaId = (String) params.getOrDefault("schemaId", "");
        int maxIterations = (int) params.getOrDefault("maxIterations", 5);
        double targetPassRate = ((Number) params.getOrDefault("targetPassRate", 80.0)).doubleValue();
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaId", schemaId);
        response.put("maxIterations", maxIterations);
        response.put("targetPassRate", targetPassRate);
        response.put("status", "started");
        response.put("message", "Evolve loop initiated. Use /api/evidence/aggregate to monitor progress.");
        
        log.info("Evolve started: schema={}, maxIter={}, targetPassRate={}", schemaId, maxIterations, targetPassRate);
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/evolve/status")
    public ResponseEntity<Map<String, Object>> getEvolveStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        
        status.put("phase", "ready");
        status.put("currentIteration", 0);
        status.put("passRate", 0.0);
        status.put("history", List.of());
        
        try {
            Path evidenceDir = EVIDENCE_DIR;
            if (Files.exists(evidenceDir)) {
                long count = Files.list(evidenceDir).filter(p -> p.toString().endsWith(".json")).count();
                status.put("totalRuns", count);
            }
        } catch (Exception e) {
            log.error("Error getting evolve status: {}", e.getMessage());
        }
        
        return ResponseEntity.ok(status);
    }

    @PostMapping("/evolve/stop")
    public ResponseEntity<Map<String, Object>> stopEvolve() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "stopped");
        response.put("message", "Evolve loop stopped");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        
        try {
            Path harnessDir = HARNESS_DIR;
            List<Map<String, Object>> components = new ArrayList<>();
            
            String[] comps = {"system_prompt", "tool_specs", "skills", "sub_agents", "memory", "middleware"};
            for (String comp : comps) {
                Path compPath = harnessDir.resolve(comp);
                if (Files.exists(compPath)) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", comp);
                    c.put("exists", true);
                    components.add(c);
                }
            }
            dashboard.put("components", components);
            
            int evidenceCount = 0;
            Path evidenceDir = EVIDENCE_DIR;
            if (Files.exists(evidenceDir)) {
                evidenceCount = (int) Files.list(evidenceDir).filter(p -> p.toString().endsWith(".json")).count();
            }
            dashboard.put("evidenceCount", evidenceCount);
            
            Path manifestFile = harnessDir.resolve("manifest.yaml");
            dashboard.put("manifestExists", Files.exists(manifestFile));
            
        } catch (Exception e) {
            log.error("Error building dashboard: {}", e.getMessage());
        }
        
        return ResponseEntity.ok(dashboard);
    }
}