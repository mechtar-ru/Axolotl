package com.agent.orchestrator.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;

@Component
@ConfigurationProperties(prefix = "axolotl.apps")
public class AppConfig {

    /** Base directory where app projects are stored, e.g. /Users/name/git/Axolotl */
    private String basePath = ".";

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    /**
     * Resolve relative basePath to absolute on startup.
     * If basePath is ".", resolve it to the project root directory
     * (handles Maven dev mode where CWD = backend/).
     */
    @PostConstruct
    public void init() {
        if (basePath == null || basePath.isEmpty()) {
            basePath = ".";
        }
        // Resolve relative to absolute
        java.nio.file.Path resolved = Paths.get(basePath).toAbsolutePath().normalize();
        // Maven dev mode: CWD = backend/ → project root is one level up
        if (resolved.endsWith("backend") && resolved.resolve("../pom.xml").toFile().exists()) {
            resolved = resolved.getParent();
        }
        this.basePath = resolved.toString();
    }

    /** Compute target path for a given app name */
    public String targetPathFor(String appName) {
        return basePath + "/" + appName + "/";
    }
}
