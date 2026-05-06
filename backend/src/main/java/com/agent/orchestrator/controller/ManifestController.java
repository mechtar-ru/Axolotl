package com.agent.orchestrator.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/manifest")
public class ManifestController {

    private static final Logger log = LoggerFactory.getLogger(ManifestController.class);
    private static final Path MANIFEST_FILE = Paths.get("/Users/evgenijtihomirov/git/Axolotl/Axolotl/harness/manifest.yaml");

    @GetMapping
    public ResponseEntity<Map<String, Object>> getManifest() {
        try {
            if (!Files.exists(MANIFEST_FILE)) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("edits", List.of());
                return ResponseEntity.ok(empty);
            }
            
            String content = Files.readString(MANIFEST_FILE);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("content", content);
            manifest.put("path", MANIFEST_FILE.toString());
            
            return ResponseEntity.ok(manifest);
        } catch (Exception e) {
            log.error("Error reading manifest: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/edits")
    public ResponseEntity<Map<String, Object>> addEdit(@RequestBody Map<String, Object> edit) {
        try {
            List<Map<String, Object>> edits = new ArrayList<>();
            
            if (Files.exists(MANIFEST_FILE)) {
                String content = Files.readString(MANIFEST_FILE);
                if (content.contains("edits:")) {
                    log.info("Appending to existing manifest");
                }
            }
            
            String editId = "evolve-" + System.currentTimeMillis();
            edit.put("id", editId);
            edit.put("timestamp", new Date().toString());
            edit.put("status", "pending");
            
            edits.add(edit);
            
            StringBuilder sb = new StringBuilder();
            sb.append("# Evolve Manifest\n");
            sb.append("# Records each component edit with prediction\n\n");
            sb.append("edits:\n");
            for (Map<String, Object> e : edits) {
                sb.append("  - id: \"").append(e.get("id")).append("\"\n");
                sb.append("    type: \"").append(e.getOrDefault("type", "improvement")).append("\"\n");
                sb.append("    description: \"").append(e.getOrDefault("description", "")).append("\"\n");
                sb.append("    files: [").append(e.getOrDefault("files", "")).append("]\n");
                sb.append("    prediction:\n");
                sb.append("      fixes: []\n");
                sb.append("      regresses: []\n");
                sb.append("    rationale: \"").append(e.getOrDefault("rationale", "")).append("\"\n");
                sb.append("    status: \"").append(e.getOrDefault("status", "pending")).append("\"\n\n");
            }
            
            Files.writeString(MANIFEST_FILE, sb.toString());
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "saved");
            response.put("editId", editId);
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error saving manifest: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/edits/{editId}/verify")
    public ResponseEntity<Map<String, Object>> verifyEdit(
            @PathVariable String editId,
            @RequestBody Map<String, Object> verification) {
        
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("editId", editId);
            response.put("verified", verification.getOrDefault("verified", false));
            response.put("message", "Edit verification recorded");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error verifying edit: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}