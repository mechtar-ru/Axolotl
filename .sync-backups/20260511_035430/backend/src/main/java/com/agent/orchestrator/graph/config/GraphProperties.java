package com.agent.orchestrator.graph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "axolotl.graph")
public class GraphProperties {

    private boolean enabled = true;
    private int tokenBudget = 2000;
    private int batchParallelism = 8;
    private int batchSize = 20;
    private String codebasePath = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(int tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public int getBatchParallelism() {
        return batchParallelism;
    }

    public void setBatchParallelism(int batchParallelism) {
        this.batchParallelism = batchParallelism;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String getCodebasePath() {
        return codebasePath;
    }

    public void setCodebasePath(String codebasePath) {
        this.codebasePath = codebasePath;
    }
}