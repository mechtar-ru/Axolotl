package com.agent.orchestrator.controller;

import com.agent.orchestrator.service.LspManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lsp")
public class LspController {

    private final LspManager lspManager;

    public LspController(LspManager lspManager) {
        this.lspManager = lspManager;
    }

    /**
     * Start a language server for a project.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startServer(@RequestBody StartRequest request) {
        boolean success = lspManager.startServer(request.language(), request.projectRoot());
        return success
                ? ResponseEntity.ok(Map.of("status", "started"))
                : ResponseEntity.badRequest().body(Map.of("error", "Failed to start server"));
    }

    /**
     * Stop a language server.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stopServer(@RequestBody StopRequest request) {
        lspManager.stopServer(request.language(), request.projectRoot());
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    /**
     * Get hover information at a position.
     */
    @GetMapping("/hover")
    public String hover(@RequestParam String language,
                          @RequestParam String projectRoot,
                          @RequestParam String fileUri,
                          @RequestParam int line,
                          @RequestParam int character) {
        return lspManager.hover(language, projectRoot, fileUri, line, character);
    }

    /**
     * Get definition location.
     */
    @GetMapping("/definition")
    public String definition(@RequestParam String language,
                            @RequestParam String projectRoot,
                            @RequestParam String fileUri,
                            @RequestParam int line,
                            @RequestParam int character) {
        return lspManager.definition(language, projectRoot, fileUri, line, character);
    }

    /**
     * Get references.
     */
    @GetMapping("/references")
    public List<String> references(@RequestParam String language,
                                  @RequestParam String projectRoot,
                                  @RequestParam String fileUri,
                                  @RequestParam int line,
                                  @RequestParam int character) {
        return lspManager.references(language, projectRoot, fileUri, line, character);
    }

    /**
     * Get document symbols.
     */
    @GetMapping("/documentSymbols")
    public List<String> documentSymbols(@RequestParam String language,
                                       @RequestParam String projectRoot,
                                       @RequestParam String fileUri) {
        return lspManager.documentSymbols(language, projectRoot, fileUri);
    }

    /**
     * Get completions at a position.
     */
    @GetMapping("/completions")
    public List<String> completions(@RequestParam String language,
                                  @RequestParam String projectRoot,
                                  @RequestParam String fileUri,
                                  @RequestParam int line,
                                  @RequestParam int character) {
        return lspManager.completions(language, projectRoot, fileUri, line, character);
    }

    // Request DTOs
    public record StartRequest(String language, String projectRoot) {}
    public record StopRequest(String language, String projectRoot) {}
}
