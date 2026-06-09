package com.agent.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central feature flag service.
 * Flags are read from application.yml (axolotl.features.*) at startup
 * and can be overridden at runtime via the Settings API.
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final Map<String, Boolean> runtimeOverrides = new ConcurrentHashMap<>();

    private final boolean webhookEnabled;
    private final boolean graphQueryEnabled;
    private final boolean tddPipelineEnabled;
    private final boolean orchestrationEnabled;
    private final boolean experimentalDraftNodeEnabled;

    public FeatureFlagService(
            @Value("${axolotl.features.webhook.enabled:false}") boolean webhookEnabled,
            @Value("${axolotl.features.graph-query.enabled:true}") boolean graphQueryEnabled,
            @Value("${axolotl.features.tdd-pipeline.enabled:true}") boolean tddPipelineEnabled,
            @Value("${axolotl.features.orchestration.enabled:true}") boolean orchestrationEnabled,
            @Value("${axolotl.features.experimental.draft-node.enabled:true}") boolean experimentalDraftNodeEnabled) {
        this.webhookEnabled = webhookEnabled;
        this.graphQueryEnabled = graphQueryEnabled;
        this.tddPipelineEnabled = tddPipelineEnabled;
        this.orchestrationEnabled = orchestrationEnabled;
        this.experimentalDraftNodeEnabled = experimentalDraftNodeEnabled;
        log.info("Feature flags initialized: webhook={}, graphQuery={}, tdd={}, orchestration={}, experimental={}",
                webhookEnabled, graphQueryEnabled, tddPipelineEnabled, orchestrationEnabled, experimentalDraftNodeEnabled);
    }

    public boolean isEnabled(String flagName) {
        Boolean override = runtimeOverrides.get(flagName);
        if (override != null) return override;
        return switch (flagName) {
            case "webhook" -> webhookEnabled;
            case "graph-query" -> graphQueryEnabled;
            case "tdd-pipeline" -> tddPipelineEnabled;
            case "orchestration" -> orchestrationEnabled;
            case "experimental.draft-node" -> experimentalDraftNodeEnabled;
            default -> {
                log.warn("Unknown feature flag: {}", flagName);
                yield false;
            }
        };
    }

    public void setOverride(String flagName, boolean enabled) {
        runtimeOverrides.put(flagName, enabled);
        log.info("Feature flag override: {} = {}", flagName, enabled);
    }

    public void clearOverride(String flagName) {
        runtimeOverrides.remove(flagName);
    }

    public Map<String, Boolean> getAllFlags() {
        return Map.of(
                "webhook", isEnabled("webhook"),
                "graph-query", isEnabled("graph-query"),
                "tdd-pipeline", isEnabled("tdd-pipeline"),
                "orchestration", isEnabled("orchestration"),
                "experimental.draft-node", isEnabled("experimental.draft-node")
        );
    }
}
