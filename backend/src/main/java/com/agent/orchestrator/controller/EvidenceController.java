package com.agent.orchestrator.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private static final Logger log = LoggerFactory.getLogger(EvidenceController.class);
    private static final Path EVIDENCE_DIR = Paths.get("/Users/evgenijtihomirov/git/Axolotl/Axolotl/harness/evidence");

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listEvidence(
            @RequestParam(required = false) String schemaId) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        
        try {
            if (!Files.exists(EVIDENCE_DIR)) {
                return ResponseEntity.ok(List.of());
            }
            
            try (var stream = Files.list(EVIDENCE_DIR)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                      .forEach(p -> {
                          try {
                              String content = Files.readString(p);
                              Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper()
                                      .readValue(content, Map.class);
                              
                              if (schemaId == null || schemaId.equals(data.get("schemaId"))) {
                                  Map<String, Object> entry = new LinkedHashMap<>();
                                  entry.put("id", p.getFileName().toString().replace(".json", ""));
                                  entry.putAll(data);
                                  evidence.add(entry);
                              }
                          } catch (Exception e) {
                              log.error("Error reading evidence file: {}", e.getMessage(), e);
                          }
                      });
            }
        } catch (Exception e) {
            log.error("Error listing evidence: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
        
        return ResponseEntity.ok(evidence);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<Map<String, Object>> getEvidence(@PathVariable String runId) {
        try {
            Path file = EVIDENCE_DIR.resolve(runId + ".json");
            if (!Files.exists(file)) {
                return ResponseEntity.notFound().build();
            }
            
            String content = Files.readString(file);
            Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(content, Map.class);
            
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error reading evidence: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createEvidence(@RequestBody Map<String, Object> payload) {
        try {
            Files.createDirectories(EVIDENCE_DIR);
            
            String schemaId = (String) payload.getOrDefault("schemaId", "unknown");
            long timestamp = System.currentTimeMillis();
            String runId = schemaId + "_" + timestamp;
            
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("runId", runId);
            evidence.put("schemaId", schemaId);
            evidence.put("timestamp", new Date(timestamp).toString());
            evidence.put("outcome", payload.getOrDefault("outcome", "UNKNOWN"));
            evidence.put("rootCause", payload.getOrDefault("rootCause", ""));
            evidence.put("toolCalls", payload.getOrDefault("toolCalls", List.of()));
            evidence.put("tokensUsed", payload.getOrDefault("tokensUsed", 0));
            evidence.put("durationMs", payload.getOrDefault("durationMs", 0));
            
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(evidence);
            
            Files.writeString(EVIDENCE_DIR.resolve(runId + ".json"), json);
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "saved");
            response.put("runId", runId);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving evidence: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/aggregate")
    public ResponseEntity<Map<String, Object>> getAggregate() {
        Map<String, Object> aggregate = new LinkedHashMap<>();
        
        try {
            if (!Files.exists(EVIDENCE_DIR)) {
                aggregate.put("totalRuns", 0);
                aggregate.put("passed", 0);
                aggregate.put("failed", 0);
                return ResponseEntity.ok(aggregate);
            }
            
            int total = 0;
            int passed = 0;
            int failed = 0;
            long totalTokens = 0;
            long totalDuration = 0;
            Map<String, Integer> toolUsage = new LinkedHashMap<>();
            List<Map<String, Object>> allEvidence = new ArrayList<>();
            
            try (var stream = Files.list(EVIDENCE_DIR)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                      .forEach(p -> {
                          try {
                              String content = Files.readString(p);
                              Map<String, Object> data = new com.fasterxml.jackson.databind.ObjectMapper()
                                      .readValue(content, Map.class);
                              allEvidence.add(data);
                          } catch (Exception e) {
                              log.error("Error processing evidence file: {}", e.getMessage(), e);
                          }
                      });
            }
            
            for (Map<String, Object> data : allEvidence) {
                total++;
                if ("PASS".equals(data.get("outcome"))) passed++;
                else if ("FAIL".equals(data.get("outcome"))) failed++;
                
                Object tokens = data.get("tokensUsed");
                if (tokens instanceof Number) {
                    totalTokens += ((Number) tokens).longValue();
                }
                
                Object duration = data.get("durationMs");
                if (duration instanceof Number) {
                    totalDuration += ((Number) duration).longValue();
                }
                
                List<Map<String, Object>> calls = (List<Map<String, Object>>) data.get("toolCalls");
                if (calls != null) {
                    for (Map<String, Object> call : calls) {
                        String tool = (String) call.getOrDefault("tool", "unknown");
                        toolUsage.put(tool, toolUsage.getOrDefault(tool, 0) + 1);
                    }
                }
            }
            
            aggregate.put("totalRuns", total);
            aggregate.put("passed", passed);
            aggregate.put("failed", failed);
            aggregate.put("passRate", total > 0 ? (double) passed / total * 100 : 0);
            aggregate.put("totalTokens", totalTokens);
            aggregate.put("totalDurationMs", totalDuration);
            aggregate.put("avgDurationMs", total > 0 ? totalDuration / total : 0);
            aggregate.put("toolUsage", toolUsage);
            
        } catch (Exception e) {
            log.error("Error aggregating evidence: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
        
        return ResponseEntity.ok(aggregate);
    }
}