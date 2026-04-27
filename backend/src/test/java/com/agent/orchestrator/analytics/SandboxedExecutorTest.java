package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SandboxedExecutorTest {
    
    @Test
    void testSandboxPolicyCreation() {
        SandboxedExecutor.SandboxPolicy policy = new SandboxedExecutor.SandboxPolicy();
        
        assertNotNull(policy.getWorkingDirectory());
        assertFalse(policy.isNetworkAllowed());
        assertEquals(256, policy.getMemoryLimitMb());
    }
    
    @Test
    void testSandboxPolicyConfiguration() {
        SandboxedExecutor.SandboxPolicy policy = new SandboxedExecutor.SandboxPolicy();
        
        policy.setWorkingDirectory("/tmp/sandbox");
        policy.setAllowNetwork(true);
        policy.setMemoryLimitMb(512);
        policy.setAllowedPaths(Arrays.asList("/tmp", "/var/tmp"));
        
        assertEquals("/tmp/sandbox", policy.getWorkingDirectory());
        assertTrue(policy.isNetworkAllowed());
        assertEquals(512, policy.getMemoryLimitMb());
        assertEquals(2, policy.getAllowedPaths().size());
    }
    
    @Test
    void testExecuteSimpleCommand() {
        SandboxedExecutor executor = new SandboxedExecutor();
        
        SandboxedExecutor.SandboxPolicy policy = new SandboxedExecutor.SandboxPolicy();
        
        SandboxedExecutor.ExecutionResult result = 
            executor.executeTool("echo", Arrays.asList("hello"), policy, 5000);
        
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("hello"));
        assertFalse(result.isTimedOut());
        assertTrue(result.isSuccess());
    }
    
    @Test
    void testCommandWithError() {
        SandboxedExecutor executor = new SandboxedExecutor();
        
        SandboxedExecutor.ExecutionResult result = 
            executor.executeTool("ls", Arrays.asList("/nonexistent/path"), null);
        
        assertNotEquals(0, result.getExitCode());
    }
    
    @Test
    void testTimeoutEnforcement() {
        SandboxedExecutor executor = new SandboxedExecutor();
        
        SandboxedExecutor.ExecutionResult result = 
            executor.executeTool("sleep", Arrays.asList("2"), null, 100);
        
        assertTrue(result.isTimedOut());
    }
    
    @Test
    void testExecutionResultProperties() {
        SandboxedExecutor.ExecutionResult result = 
            new SandboxedExecutor.ExecutionResult(0, "output", "error", 100, false);
        
        assertEquals(0, result.getExitCode());
        assertEquals("output", result.getStdout());
        assertEquals("error", result.getStderr());
        assertEquals(100, result.getExecutionTimeMs());
        assertFalse(result.isTimedOut());
        assertTrue(result.isSuccess());
    }
    
    @Test
    void testExecuteScript() {
        SandboxedExecutor executor = new SandboxedExecutor();
        
        String script = "#!/bin/bash\necho 'Script executed'\n";
        
        SandboxedExecutor.ExecutionResult result = 
            executor.executeScript(script, null, 5000);
        
        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("Script executed"));
    }
    
    @Test
    void testExecutorConfiguration() {
        SandboxedExecutor executor = new SandboxedExecutor();
        
        executor.setDefaultTimeoutMs(60000);
        executor.setMaxMemoryMb(512);
        executor.setMaxCpuTimeMs(30000);
        
        assertEquals(60000, executor.getDefaultTimeoutMs());
        assertEquals(512, executor.getMaxMemoryMb());
        assertEquals(30000, executor.getMaxCpuTimeMs());
    }
    
    @Test
    void testExecutionWithNullArgs() {
        SandboxedExecutor executor = new SandboxedExecutor();
        
        SandboxedExecutor.ExecutionResult result = 
            executor.executeTool("pwd", null, null);
        
        assertEquals(0, result.getExitCode());
        assertNotNull(result.getStdout());
    }
    
    @Test
    void testNullStdinHandled() {
        SandboxedExecutor.ExecutionResult result = 
            new SandboxedExecutor.ExecutionResult(0, null, null, 50, false);
        
        assertEquals(0, result.getExitCode());
        assertEquals("", result.getStdout());
        assertEquals("", result.getStderr());
    }
}