package com.agent.orchestrator.controller;

import com.agent.orchestrator.service.CrossCheckService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/crosscheck")
public class CrossCheckController {

    private final CrossCheckService crossCheckService;

    public CrossCheckController(CrossCheckService crossCheckService) {
        this.crossCheckService = crossCheckService;
    }

    @PostMapping
    public CrossCheckService.CrossCheckResult verify(@RequestBody CrossCheckRequest request) {
        return crossCheckService.verify(
                request.agentName,
                request.originalOutput,
                request.context
        );
    }

    @GetMapping("/{agentName}")
    public CrossCheckService.CrossCheckResult getLastResult(@PathVariable String agentName) {
        var result = crossCheckService.getLastResult(agentName);
        if (result == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "No cross-check result for " + agentName);
        }
        return result;
    }

    public static class CrossCheckRequest {
        public String agentName;
        public String originalOutput;
        public String context;
    }
}
