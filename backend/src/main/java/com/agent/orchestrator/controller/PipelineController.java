package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.ExecutionRun;
import com.agent.orchestrator.model.Pipeline;
import com.agent.orchestrator.model.WorkflowSchema;
import com.agent.orchestrator.service.PipelineService;
import com.agent.orchestrator.service.SchemaValidationException;
import com.agent.orchestrator.service.SchemaService;
import com.agent.orchestrator.service.SettingsService;
import com.agent.orchestrator.repository.ExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);

    private final SchemaService schemaService;
    private final PipelineService pipelineService;
    private final SettingsService settingsService;
    private final ExecutionRepository executionRepository;

    public PipelineController(SchemaService schemaService,
                              PipelineService pipelineService,
                              SettingsService settingsService,
                              ExecutionRepository executionRepository) {
        this.schemaService = schemaService;
        this.pipelineService = pipelineService;
        this.settingsService = settingsService;
        this.executionRepository = executionRepository;
    }

    // ── Multi-Stage Pipeline ────────────────────────────────────

    /**
     * @deprecated All schemas use canvas-derived execution since June 2026.
     * Use POST /schemas/{id}/execute instead.
     */
    @Deprecated
    @PostMapping("/schemas/{id}/pipeline/build")
    public Map<String, Object> buildPipelineNodes(@PathVariable String id) {
        log.warn("pipeline/build called for {} — deprecated, redirecting to /execute", id);
        schemaService.executeSchema(id);
        return Map.of("status", "ok", "message", "Execution started (pipeline/build deprecated)");
    }

    /**
     * @deprecated Unified execution. Use POST /schemas/{id}/execute instead.
     */
    @Deprecated
    @PostMapping("/schemas/{id}/pipeline/execute")
    public ResponseEntity<Map<String, Object>> executePipeline(@PathVariable String id) {
        try {
            schemaService.executeSchema(id);
            return ResponseEntity.ok(Map.of("status", "ok", "message", "Pipeline execution started"));
        } catch (SchemaValidationException e) {
            log.warn("Pipeline execution blocked by validation: {} error(s)", e.getValidationResult().getErrors().size());
            Map<String, Object> result = new HashMap<>();
            result.put("status", "validation_error");
            result.put("validation", e.getValidationResult());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/schemas/{id}/pipeline/retry")
    public Map<String, String> retryPipeline(@PathVariable String id) {
        try {
            pipelineService.retryPipeline(id);
            return Map.of("status", "ok", "message", "Pipeline retry started from first failed stage");
        } catch (Exception e) {
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @PostMapping("/schemas/{id}/pipeline/cancel")
    public Map<String, String> cancelPipeline(@PathVariable String id) {
        pipelineService.cancelPipeline(id);
        return Map.of("status", "ok", "message", "Pipeline cancelled");
    }

    @GetMapping("/schemas/{id}/pipeline/status")
    public Map<String, Object> pipelineStatus(@PathVariable String id) {
        boolean running = pipelineService.isPipelineRunning(id);
        Map<String, String> results = pipelineService.getStageResults(id);
        ExecutionRun lastRun = executionRepository.getLatestRunBySchema(id);
        String lastRunStatus = lastRun != null ? lastRun.getStatus() : null;
        String lastRunError = lastRun != null ? lastRun.getError() : null;
        return Map.of("running", running, "stageResults", results,
                "lastRunStatus", lastRunStatus, "lastRunError", lastRunError);
    }

    @PostMapping("/schemas/{id}/pipeline/default")
    public Map<String, Object> createDefaultPipeline(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        WorkflowSchema schema = schemaService.getSchema(id);
        if (schema == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Schema not found");
        }
        String appType = (String) body.getOrDefault("appType", "custom");
        String description = (String) body.getOrDefault("description", schema.getDescription());
        boolean tddEnabled = Boolean.TRUE.equals(body.getOrDefault("tddEnabled", false));
        Pipeline pipeline = PipelineService.createDefaultPipeline(appType, description);
        pipeline.setTddEnabled(tddEnabled);
        PipelineService.expandTddStages(pipeline);
        schema.setPipeline(pipeline);

        // Use user's default model — execution engine handles routing/availability
        String globalModel = settingsService.getGlobalDefaultModel();
        schema.setDefaultModel(globalModel != null && !globalModel.isBlank()
                ? globalModel : "deepseek-v4-flash-free");
        if (schema.getPipeline() != null && schema.getPipeline().getStages() != null) {
            for (var stage : schema.getPipeline().getStages()) {
                if (stage.getModel() == null || stage.getModel().isBlank()) {
                    stage.setModel(schema.getDefaultModel());
                }
            }
        }

        schemaService.updateSchema(id, schema);
        return Map.of("status", "ok", "pipeline", tddEnabled ? "TDD pipeline created" : "Default pipeline created");
    }
}
