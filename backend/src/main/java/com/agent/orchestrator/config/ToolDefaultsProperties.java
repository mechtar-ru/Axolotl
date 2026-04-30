package com.agent.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Global tool defaults from application.yml (axolotl.tools.defaults).
 */
@Component
@ConfigurationProperties(prefix = "axolotl.tools.defaults")
public class ToolDefaultsProperties {

    private List<String> enabled;
    private Map<String, String> permissions;

    public List<String> getEnabled() {
        return enabled != null ? enabled : List.of("file_read", "file_write", "directory_read", "bash");
    }

    public Map<String, String> getPermissions() {
        return permissions != null ? permissions : Map.of(
                "file_read", "read",
                "file_write", "ask",
                "directory_read", "read",
                "bash", "ask"
        );
    }

    public void setEnabled(List<String> enabled) { this.enabled = enabled; }
    public void setPermissions(Map<String, String> permissions) { this.permissions = permissions; }
}
