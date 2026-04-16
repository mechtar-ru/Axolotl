package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SkillService {
    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    public SkillService() {
        initDefaultSkills();
    }

    private void initDefaultSkills() {
        addSkill(new Skill(
            "Code Review",
            "Анализ и ревью кода",
            "Проведи детальный ревью следующего кода:\n\n{{code}}\n\nОцени: читаемость, безопасность, производительность, паттерны.",
            "(код|ревью|review|анализ.*кода)"
        ));

        addSkill(new Skill(
            "Bug Analysis",
            "Анализ и поиск багов",
            "Проанализируй следующий код на наличие багов:\n\n{{code}}\n\nОпиши найденные проблемы и предложи исправления.",
            "(баг|bug|ошибка|issue|дефект)"
        ));

        addSkill(new Skill(
            "Documentation",
            "Генерация документации",
            "Сгенерируй документацию для следующего кода:\n\n{{code}}\n\nВключи: описание функций, параметры, примеры использования.",
            "(документ|doc|документация|описание)"
        ));

        addSkill(new Skill(
            "Refactoring",
            "Рефакторинг кода",
            "Предложи улучшения для следующего кода:\n\n{{code}}\n\nФокус на: читаемость, производительность, паттерны.",
            "(рефактор|refactor|улучш|оптимиз)"
        ));

        log.info("Initialized {} default skills", skills.size());
    }

    public Skill addSkill(Skill skill) {
        skills.put(skill.getId(), skill);
        log.info("Skill added: {}", skill.getName());
        return skill;
    }

    public Optional<Skill> getSkill(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }

    public List<Skill> getEnabledSkills() {
        return skills.values().stream()
                .filter(Skill::isEnabled)
                .collect(Collectors.toList());
    }

    public List<Skill> findMatchingSkills(String query) {
        return getEnabledSkills().stream()
                .filter(skill -> matchesTrigger(skill.getTriggerPattern(), query))
                .sorted(Comparator.comparingInt(Skill::getUsageCount).reversed())
                .collect(Collectors.toList());
    }

    private boolean matchesTrigger(String pattern, String query) {
        if (pattern == null || pattern.isBlank()) return false;
        try {
            Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            return regex.matcher(query).find();
        } catch (Exception e) {
            return query.toLowerCase().contains(pattern.toLowerCase());
        }
    }

    public void recordUsage(String skillId, boolean success) {
        skills.computeIfPresent(skillId, (id, skill) -> {
            skill.incrementUsage();
            skill.setLastUsedAt(Instant.now());
            if (success && skill.getUsageCount() > 0) {
                skill.setSuccessRate(
                    (skill.getSuccessRate() * (skill.getUsageCount() - 1) + 1.0) / skill.getUsageCount()
                );
            }
            return skill;
        });
    }

    public void deleteSkill(String id) {
        skills.remove(id);
        log.info("Skill deleted: {}", id);
    }

    public Skill updateSkill(String id, Skill updated) {
        skills.computeIfPresent(id, (key, existing) -> {
            existing.setName(updated.getName());
            existing.setDescription(updated.getDescription());
            existing.setPromptTemplate(updated.getPromptTemplate());
            existing.setTriggerPattern(updated.getTriggerPattern());
            existing.setCategory(updated.getCategory());
            existing.setEnabled(updated.isEnabled());
            return existing;
        });
        return skills.get(id);
    }
}
