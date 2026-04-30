package com.agent.orchestrator.controller;

import com.agent.orchestrator.model.Skill;
import com.agent.orchestrator.service.SkillService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public List<Skill> getAllSkills() {
        return skillService.getAllSkills();
    }

    @GetMapping("/enabled")
    public List<Skill> getEnabledSkills() {
        return skillService.getEnabledSkills();
    }

    @GetMapping("/match")
    public List<Skill> findMatchingSkills(@RequestParam String query) {
        return skillService.findMatchingSkills(query);
    }

    @GetMapping("/{id}")
    public Skill getSkill(@PathVariable String id) {
        Skill skill = skillService.getSkill(id);
        if (skill == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Skill not found: " + id);
        }
        return skill;
    }

    @PostMapping
    public Skill createSkill(@RequestBody Skill skill) {
        return skillService.addSkill(skill);
    }

    @PutMapping("/{id}")
    public Skill updateSkill(@PathVariable String id, @RequestBody Skill skill) {
        return skillService.updateSkill(id, skill);
    }

    @DeleteMapping("/{id}")
    public void deleteSkill(@PathVariable String id) {
        skillService.deleteSkill(id);
    }

    @PostMapping("/{id}/usage")
    public void recordUsage(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        Boolean success = body.getOrDefault("success", false);
        skillService.recordUsage(id, success);
    }

    /**
     * Search agentskills.io registry.
     */
    @GetMapping("/search")
    public List<Map<String, Object>> searchSkills(@RequestParam String query) {
        return skillService.searchInRegistry(query);
    }

    /**
     * Validate skill spec compliance.
     */
    @PostMapping("/{id}/validate")
    public Map<String, Object> validateSkill(@PathVariable String id) {
        Skill skill = skillService.getSkill(id);
        if (skill == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Skill not found: " + id);
        }
        return skillService.validateSpecCompliance(skill);
    }

    /**
     * Sync skills from registry.
     */
    @PostMapping("/sync")
    public Map<String, Object> syncSkills() {
        return skillService.syncFromRegistry();
    }

    /**
     * Discover available skills from registry.
     */
    @GetMapping("/discover")
    public List<Map<String, Object>> discoverSkills() {
        return skillService.discoverFromRegistry();
    }
}
