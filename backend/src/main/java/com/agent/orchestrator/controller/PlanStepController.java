package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.PlanStep;
import com.agent.orchestrator.model.PlanStepStatus;
import com.agent.orchestrator.service.PlanStepService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plan-steps")
public class PlanStepController {

    private final PlanStepService planStepService;

    public PlanStepController(PlanStepService planStepService) {
        this.planStepService = planStepService;
    }

    @GetMapping("/{schemaId}")
    public List<PlanStep> getSteps(@PathVariable String schemaId) {
        return planStepService.getSteps(schemaId);
    }

    @GetMapping("/{schemaId}/steps/{stepId}")
    public ResponseEntity<PlanStep> getStep(@PathVariable String schemaId,
                                            @PathVariable String stepId) {
        PlanStep step = planStepService.getStep(schemaId, stepId);
        if (step == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(step);
    }

    @PostMapping("/{schemaId}")
    public List<PlanStep> createSteps(@PathVariable String schemaId,
                                       @RequestBody List<PlanStep> steps) {
        return planStepService.createSteps(schemaId, steps);
    }

    @PutMapping("/{schemaId}/steps/{stepId}/status")
    public PlanStep updateStatus(@PathVariable String schemaId,
                                  @PathVariable String stepId,
                                  @RequestBody UpdateStatusRequest request) {
        return planStepService.updateStatus(schemaId, stepId, request.status(), request.reason());
    }

    @GetMapping("/{schemaId}/ready")
    public List<PlanStep> getReadySteps(@PathVariable String schemaId) {
        return planStepService.getReadySteps(schemaId);
    }

    @GetMapping("/{schemaId}/graph")
    public Map<String, Object> getDependencyGraph(@PathVariable String schemaId) {
        return planStepService.getDependencyGraph(schemaId);
    }

    @DeleteMapping("/{schemaId}")
    public ResponseEntity<Map<String, String>> deleteSteps(@PathVariable String schemaId) {
        planStepService.deleteSteps(schemaId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/{schemaId}/sync-to-disk")
    public ResponseEntity<Map<String, String>> syncToDisk(@PathVariable String schemaId) {
        String result = planStepService.syncToDisk(schemaId);
        return ResponseEntity.ok(Map.of("result", result));
    }

    public record UpdateStatusRequest(PlanStepStatus status, String reason) {}
}
