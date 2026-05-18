package com.agent.orchestrator.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

@Service
public class PluginService {

    private final Map<String, PluginInfo> registry = new ConcurrentHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    private static final Set<String> ALLOWED_MANAGERS = Set.of("pip", "npm", "opm");
    private static final Set<String> ALLOWED_NAMES = Set.of("mempalace", "btca", "context7");

    private static final Map<String, Integer> PLUGIN_PORTS = Map.of(
        "mempalace", 5890
    );

    private static final Map<String, String> PLUGIN_START_COMMANDS = Map.of(
        "mempalace", "python -m mempalace.mcp_server --http --port 5890"
    );

    public PluginInfo install(String name, String manager) {
        if (!ALLOWED_NAMES.contains(name)) {
            throw new IllegalArgumentException("Plugin not in whitelist: " + name);
        }
        if (!ALLOWED_MANAGERS.contains(manager)) {
            throw new IllegalArgumentException("Manager not in whitelist: " + manager);
        }

        String installCommand = buildInstallCommand(name, manager);
        String output = runCommand(installCommand, 120);
        String version = parseVersion(output);

        PluginInfo info = new PluginInfo(name, manager, version, "installed", getPort(name), null);
        registry.put(name, info);
        return info;
    }

    public Collection<PluginInfo> list() {
        return registry.values();
    }

    public PluginInfo start(String name) {
        PluginInfo info = registry.get(name);
        if (info == null) {
            throw new IllegalArgumentException("Plugin not installed: " + name);
        }
        if ("running".equals(info.getStatus())) {
            return info;
        }

        String startCommand = getStartCommand(name);
        if (startCommand == null) {
            throw new IllegalArgumentException("No start command for plugin: " + name);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", startCommand);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            processes.put(name, process);
            info.setStatus("running");
            info.setPid(process.pid());
            info.setPort(getPort(name));

            CompletableFuture.runAsync(() -> {
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    info.setStatus("stopped");
                    info.setPid(null);
                    processes.remove(name);
                }
            });

            return info;
        } catch (Exception e) {
            throw new RuntimeException("Failed to start plugin " + name + ": " + e.getMessage(), e);
        }
    }

    public PluginInfo stop(String name) {
        PluginInfo info = registry.get(name);
        if (info == null) {
            throw new IllegalArgumentException("Plugin not installed: " + name);
        }

        Process process = processes.remove(name);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }

        info.setStatus("stopped");
        info.setPid(null);
        return info;
    }

    String getStartCommand(String name) {
        return PLUGIN_START_COMMANDS.get(name);
    }

    Integer getPort(String name) {
        return PLUGIN_PORTS.get(name);
    }

    private String buildInstallCommand(String name, String manager) {
        return switch (manager) {
            case "pip" -> "pip install " + name;
            case "npm" -> "npm install -g " + name;
            case "opm" -> "opm install " + name;
            default -> throw new IllegalArgumentException("Unknown manager: " + manager);
        };
    }

    private String runCommand(String command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Command timed out after " + timeoutSeconds + "s: " + command);
            }

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Install failed (exit " + exitCode + "): " + output);
            }
            return output;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Command execution failed: " + e.getMessage(), e);
        }
    }

    private String parseVersion(String output) {
        Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(output);
        if (m.find()) {
            return m.group(1);
        }
        return "unknown";
    }

    public static class PluginInfo {
        private String name;
        private String manager;
        private String version;
        private String status;
        private Integer port;
        private Long pid;

        public PluginInfo(String name, String manager, String version, String status, Integer port, Long pid) {
            this.name = name;
            this.manager = manager;
            this.version = version;
            this.status = status;
            this.port = port;
            this.pid = pid;
        }

        public String getName() { return name; }
        public String getManager() { return manager; }
        public String getVersion() { return version; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
        public Long getPid() { return pid; }
        public void setPid(Long pid) { this.pid = pid; }
    }
}
