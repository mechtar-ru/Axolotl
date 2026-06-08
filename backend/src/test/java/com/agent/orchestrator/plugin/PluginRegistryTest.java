package com.agent.orchestrator.plugin;

import com.agent.orchestrator.service.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PluginRegistryTest {

    @Mock
    private ToolExecutor toolExecutor;

    // ─── Package name validation ───

    @Test
    void rejectEmptyPackageName() {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> createRegistry().installPlugin("", null));
        assertTrue(e.getMessage().contains("empty"));
    }

    @Test
    void rejectNullPackageName() {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> createRegistry().installPlugin(null, null));
        assertTrue(e.getMessage().contains("empty"));
    }

    @Test
    void rejectShellMetacharacters() {
        String[] dangerous = {
                "foo;rm -rf /",
                "foo|bar",
                "foo`id`",
                "foo$(cat /etc/passwd)",
                "foo & bar",
                "foo\nbar"
        };
        for (String input : dangerous) {
            Exception e = assertThrows(IllegalArgumentException.class,
                    () -> createRegistry().installPlugin(input, null),
                    "Should reject: " + input);
            assertTrue(e.getMessage().contains("unsafe characters") ||
                        e.getMessage().contains("Invalid npm package name"),
                    "Error for '" + input + "' should mention validation: " + e.getMessage());
        }
    }

    // ─── PluginConfig construction and defaults ───

    @Test
    void pluginConfigDefaults() {
        PluginConfig config = new PluginConfig();
        assertEquals("plugins/plugin-bridge.js", config.getBridgePath());
        assertEquals("bun", config.getBunPath());
        assertEquals(15_000, config.getStartupTimeoutMs());
        assertEquals(30_000, config.getRequestTimeoutMs());
        assertEquals(3, config.getMaxRestartAttempts());
        assertEquals(1_000, config.getRestartBackoffMs());
        assertTrue(config.isAutoUpdateEnabled());
        assertTrue(config.isAutoUpdateOnStart());
        assertTrue(config.getPlugins().isEmpty());
        assertTrue(config.getPluginConfigs().isEmpty());
    }

    // ─── PluginDefinition defaults ───

    @Test
    void pluginDefinitionDefaults() {
        PluginConfig.PluginDefinition def = new PluginConfig.PluginDefinition();
        def.setName("test-pkg");
        assertEquals("test-pkg", def.getName());
        assertEquals("latest", def.getVersion());
        assertTrue(def.isEnabled());
        assertTrue(def.isAutoUpdate());
        assertTrue(def.getConfig().isEmpty());
    }

    // ─── PluginStatus lifecycle ───

    @Test
    void pluginStatusValues() {
        PluginConfig.PluginStatus status = new PluginConfig.PluginStatus();
        status.setName("test");
        status.setStatus("running");
        status.setToolsCount(5);
        status.setVersion("1.0.0");

        assertEquals("test", status.getName());
        assertEquals("running", status.getStatus());
        assertEquals(5, status.getToolsCount());
        assertEquals("1.0.0", status.getVersion());
    }

    // ─── stopPlugin / restartPlugin error cases ───

    @Test
    void stopPluginNonExistentDoesNothing() {
        PluginRegistry registry = createRegistry();
        assertDoesNotThrow(() -> registry.stopPlugin("nonexistent"));
    }

    @Test
    void restartPluginUnknownThrows() {
        PluginRegistry registry = createRegistry();
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> registry.restartPlugin("unknown"));
        assertTrue(e.getMessage().contains("Unknown"));
    }

    // ─── getAllStatuses on empty registry ───

    @Test
    void getAllStatusesEmptyWhenNoPlugins() {
        PluginConfig config = new PluginConfig();
        PluginRegistry registry = new PluginRegistry(config, toolExecutor, "/tmp/project");
        assertTrue(registry.getAllStatuses().isEmpty());
        assertEquals(0, registry.getPluginCount());
    }

    // ─── bridge / adapter accessors ───

    @Test
    void bridgeAndAdapterAbsentForUnknownPlugin() {
        PluginRegistry registry = createRegistry();
        assertTrue(registry.getBridge("nonexistent").isEmpty());
        assertTrue(registry.getToolAdapter("nonexistent").isEmpty());
    }

    // ─── close on empty registry ───

    @Test
    void closeEmptyRegistryDoesNothing() {
        PluginRegistry registry = createRegistry();
        assertDoesNotThrow(registry::close);
    }

    // ─── Helpers ───

    private PluginRegistry createRegistry() {
        PluginConfig config = new PluginConfig();
        return new PluginRegistry(config, toolExecutor, "/tmp/test-project");
    }
}
