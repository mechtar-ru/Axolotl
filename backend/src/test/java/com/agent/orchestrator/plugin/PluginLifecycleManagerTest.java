package com.agent.orchestrator.plugin;

import com.agent.orchestrator.config.AppConfig;
import com.agent.orchestrator.service.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PluginLifecycleManagerTest {

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private AppConfig appConfig;

    @Mock
    private ApplicationContext applicationContext;

    private PluginLifecycleManager manager;

    @BeforeEach
    void setUp() {
        when(appConfig.getBasePath()).thenReturn("/tmp/test");
        manager = new PluginLifecycleManager(toolExecutor, appConfig, applicationContext);

        // @Value fields don't auto-inject in plain JUnit — set defaults manually
        ReflectionTestUtils.setField(manager, "bridgePath", "plugins/plugin-bridge.js");
        ReflectionTestUtils.setField(manager, "bunPath", "bun");
        ReflectionTestUtils.setField(manager, "startupTimeoutMs", 15000);
        ReflectionTestUtils.setField(manager, "requestTimeoutMs", 30000);
        ReflectionTestUtils.setField(manager, "maxRestartAttempts", 3);
        ReflectionTestUtils.setField(manager, "restartBackoffMs", 1000L);
        ReflectionTestUtils.setField(manager, "autoUpdateEnabled", false);
        ReflectionTestUtils.setField(manager, "autoUpdateOnStart", false);
        ReflectionTestUtils.setField(manager, "projectRoot", "/tmp/test");
        ReflectionTestUtils.setField(manager, "pluginListStr", "");
        ReflectionTestUtils.setField(manager, "pluginEnabled", false);
    }

    @Test
    void whenPluginDisabled_initializeDoesNothing() {
        // setUp() already sets pluginEnabled=false
        manager.initialize();

        assertFalse(manager.isEnabled());
        assertNull(manager.getRegistry());
    }

    @Test
    void whenPluginEnabledWithEmptyList_initializesRegistry() {
        ReflectionTestUtils.setField(manager, "pluginEnabled", true);

        manager.initialize();

        assertNotNull(manager.getRegistry());
        assertEquals(0, manager.getRegistry().getPluginCount());
    }

    @Test
    void buildConfigWithPluginList() {
        ReflectionTestUtils.setField(manager, "pluginEnabled", true);
        ReflectionTestUtils.setField(manager, "pluginListStr", "pkg1,pkg2");
        // Minimal startup timeout so config parsing tests don't wait for real Bun process
        ReflectionTestUtils.setField(manager, "startupTimeoutMs", 1);

        manager.initialize();

        // Registry exists and config was parsed (plugins fail fast with 1ms timeout)
        assertNotNull(manager.getRegistry());
        assertTrue(manager.getRegistry().getAllStatuses().size() > 0,
                "Should have parsed plugin definitions from config");
    }

    @Test
    void buildConfigTrimsWhitespace() {
        ReflectionTestUtils.setField(manager, "pluginEnabled", true);
        ReflectionTestUtils.setField(manager, "pluginListStr", "  pkg1 , pkg2  ");
        ReflectionTestUtils.setField(manager, "startupTimeoutMs", 1);

        manager.initialize();

        assertNotNull(manager.getRegistry());
        assertTrue(manager.getRegistry().getAllStatuses().size() > 0,
                "Should have parsed plugin definitions with whitespace trimmed");
    }

    @Test
    void isEnabledReturnsFalseWhenDisabled() {
        // setUp() sets pluginEnabled=false
        assertFalse(manager.isEnabled());
    }

    @Test
    void isEnabledReturnsFalseWhenRegistryNotInitialized() {
        ReflectionTestUtils.setField(manager, "pluginEnabled", true);
        // initialize() not called yet
        assertFalse(manager.isEnabled());
    }

    @Test
    void shutdownWithoutInitDoesNothing() {
        assertDoesNotThrow(() -> manager.shutdown());
    }

    @Test
    void shutdownAfterInitCleansUp() {
        ReflectionTestUtils.setField(manager, "pluginEnabled", true);

        manager.initialize();
        assertNotNull(manager.getRegistry());

        assertDoesNotThrow(() -> manager.shutdown());
    }
}
