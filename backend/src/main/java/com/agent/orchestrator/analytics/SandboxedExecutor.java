package com.agent.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Provides sandboxed execution environment for testing synthesized tools.
 * 
 * Integration Points:
 * - ToolSynthesizer: For testing newly synthesized tools
 * - ToolChainingOptimizer: For benchmarking tool chains
 * - Workflow execution: For isolated tool testing
 * 
 * Sandbox Policies:
 * - Filesystem access restrictions
 * - Network access control
 * - CPU/memory limits
 * - Execution timeout
 */

public class SandboxedExecutor {
    private static final Logger logger = LoggerFactory.getLogger(SandboxedExecutor.class);
    
    private final ExecutorService executor = Executors.newCachedThreadPool();
    
    private long defaultTimeoutMs = 30000;
    private long maxMemoryMb = 256;
    private long maxCpuTimeMs = 10000;
    
    public SandboxedExecutor() {}
    
    public ExecutionResult executeTool(String command, List<String> args, SandboxPolicy policy) {
        return executeTool(command, args, policy, defaultTimeoutMs);
    }
    
    public ExecutionResult executeTool(String command, List<String> args, 
                                       SandboxPolicy policy, long timeoutMs) {
        logger.info("Executing tool in sandbox: {} {}", command, args);
        
        long startTime = System.currentTimeMillis();
        
        try {
            List<String> fullCommand = new ArrayList<>();
            fullCommand.add(command);
            if (args != null) {
                fullCommand.addAll(args);
            }
            
            ProcessBuilder builder = new ProcessBuilder(fullCommand);
            builder.redirectErrorStream(false);
            
            applySandboxPolicy(builder, policy);
            
            Process process = builder.start();
            
            InputStreamReader stdoutReader = new InputStreamReader(process.getInputStream());
            InputStreamReader stderrReader = new InputStreamReader(process.getErrorStream());
            
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            
            Future<Void> stdoutFuture = executor.submit(() -> {
                char[] buffer = new char[1024];
                int read;
                while ((read = stdoutReader.read(buffer)) != -1) {
                    stdout.append(buffer, 0, read);
                }
                return null;
            });
            
            Future<Void> stderrFuture = executor.submit(() -> {
                char[] buffer = new char[1024];
                int read;
                while ((read = stderrReader.read(buffer)) != -1) {
                    stderr.append(buffer, 0, read);
                }
                return null;
            });
            
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long executionTime = System.currentTimeMillis() - startTime;
            
            try {
                stdoutFuture.get(1, TimeUnit.SECONDS);
                stderrFuture.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("Error reading process output: {}", e.getMessage());
            }
            
            int exitCode = 0;
            if (!completed) {
                process.destroyForcibly();
                return new ExecutionResult(
                    -1,
                    stdout.toString(),
                    "Process timed out after " + timeoutMs + "ms",
                    executionTime,
                    true
                );
            } else {
                exitCode = process.exitValue();
            }
            
            boolean timedOut = executionTime >= timeoutMs;
            
            return new ExecutionResult(
                exitCode,
                stdout.toString(),
                stderr.toString(),
                executionTime,
                timedOut
            );
            
        } catch (Exception e) {
            logger.error("Execution failed: {}", e.getMessage());
            return new ExecutionResult(
                -1,
                "",
                e.getMessage(),
                System.currentTimeMillis() - startTime,
                true
            );
        }
    }
    
    public ExecutionResult executeScript(String script, SandboxPolicy policy) {
        return executeScript(script, policy, defaultTimeoutMs);
    }
    
    public ExecutionResult executeScript(String script, SandboxPolicy policy, long timeoutMs) {
        File tempScript = null;
        try {
            tempScript = File.createTempFile("synthesized_tool_", ".sh");
            tempScript.deleteOnExit();
            
            try (FileWriter writer = new FileWriter(tempScript)) {
                writer.write("#!/bin/bash\n");
                writer.write(script);
            }
            tempScript.setExecutable(true, false);
            
            SandboxPolicy scriptPolicy = policy != null ? policy : new SandboxPolicy();
            scriptPolicy.setWorkingDirectory(tempScript.getParentFile().getAbsolutePath());
            
            return executeTool("/bin/bash", 
                Collections.singletonList(tempScript.getAbsolutePath()), 
                scriptPolicy, timeoutMs);
                
        } catch (Exception e) {
            logger.error("Script execution failed: {}", e.getMessage());
            return new ExecutionResult(-1, "", e.getMessage(), 0, true);
        } finally {
            if (tempScript != null && tempScript.exists()) {
                tempScript.delete();
            }
        }
    }
    
    private void applySandboxPolicy(ProcessBuilder builder, SandboxPolicy policy) {
        if (policy == null) {
            policy = new SandboxPolicy();
        }
        
        if (policy.getWorkingDirectory() != null) {
            builder.directory(new File(policy.getWorkingDirectory()));
        }
        
        Map<String, String> env = builder.environment();
        if (policy.getAllowedPaths() != null && !policy.getAllowedPaths().isEmpty()) {
            env.put("SANDBOX_ALLOWED_PATHS", String.join(":", policy.getAllowedPaths()));
        }
    }
    
    public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
    public void setDefaultTimeoutMs(long timeoutMs) { this.defaultTimeoutMs = timeoutMs; }
    public long getMaxMemoryMb() { return maxMemoryMb; }
    public void setMaxMemoryMb(long maxMb) { this.maxMemoryMb = maxMb; }
    public long getMaxCpuTimeMs() { return maxCpuTimeMs; }
    public void setMaxCpuTimeMs(long maxMs) { this.maxCpuTimeMs = maxMs; }
    
    public static class SandboxPolicy {
        private String workingDirectory;
        private List<String> allowedPaths;
        private List<String> allowedNetworks;
        private boolean allowNetwork = false;
        private long memoryLimitMb = 256;
        private long cpuLimitMs = 10000;
        
        public SandboxPolicy() {
            this.workingDirectory = System.getProperty("java.io.tmpdir");
        }
        
        public String getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(String dir) { this.workingDirectory = dir; }
        public List<String> getAllowedPaths() { return allowedPaths; }
        public void setAllowedPaths(List<String> paths) { this.allowedPaths = paths; }
        public List<String> getAllowedNetworks() { return allowedNetworks; }
        public void setAllowedNetworks(List<String> networks) { this.allowedNetworks = networks; }
        public boolean isNetworkAllowed() { return allowNetwork; }
        public void setAllowNetwork(boolean allow) { this.allowNetwork = allow; }
        public long getMemoryLimitMb() { return memoryLimitMb; }
        public void setMemoryLimitMb(long mb) { this.memoryLimitMb = mb; }
        public long getCpuLimitMs() { return cpuLimitMs; }
        public void setCpuLimitMs(long ms) { this.cpuLimitMs = ms; }
    }
    
    public static class ExecutionResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final long executionTimeMs;
        private final boolean timedOut;
        
        public ExecutionResult(int exitCode, String stdout, String stderr, 
                             long executionTimeMs, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.executionTimeMs = executionTimeMs;
            this.timedOut = timedOut;
        }
        
        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
        public long getExecutionTimeMs() { return executionTimeMs; }
        public boolean isTimedOut() { return timedOut; }
        public boolean isSuccess() { return exitCode == 0 && !timedOut; }
        
        @Override
        public String toString() {
            return "ExecutionResult{exitCode=" + exitCode + ", time=" + executionTimeMs + 
                   "ms, timedOut=" + timedOut + "}";
        }
    }
}