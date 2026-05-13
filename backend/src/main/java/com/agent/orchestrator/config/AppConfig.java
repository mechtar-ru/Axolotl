package com.agent.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "axolotl.apps")
public class AppConfig {

    /** Base directory where app projects are stored, e.g. /Users/name/git/Axolotl */
    private String basePath = "/Users/evgenijtihomirov/git/Axolotl";

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    /** Compute target path for a given app name */
    public String targetPathFor(String appName) {
        return basePath + "/" + appName + "/";
    }
}
