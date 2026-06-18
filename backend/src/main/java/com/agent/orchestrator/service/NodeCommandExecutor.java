package com.agent.orchestrator.service;

import com.agent.orchestrator.model.Node;
import com.agent.orchestrator.websocket.ExecutionWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class NodeCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(NodeCommandExecutor.class);

    private final ExecutionWebSocketHandler webSocketHandler;

    @Value("${axolotl.sandbox.allowedWriteDirs:.}")
    private List<String> allowedWriteDirs;

    private static final Set<String> BLOCKED_COMMAND_PATTERNS = Set.of(
            "rm -rf", "mkfs", "dd if=", ">:",
            ":(){", "forkbomb",
            "chmod 777", "chown",
            "wget", "curl",
            "python3", "python", "node", "perl",
            "socat", "nc ", "ncat",
            "passwd", "sudo", "su ");

    public NodeCommandExecutor(ExecutionWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    public String execute(Node node, String schemaId) {
        if (webSocketHandler != null) {
            webSocketHandler.sendProgress(schemaId, node.getId(), "RUNNING", 20, "Выполнение команды");
        }

        String command = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("command", "") : "";
        String workingDir = node.getData() != null && node.getData().getConfig() != null
                ? (String) node.getData().getConfig().getOrDefault("workingDir", "") : "";
        int timeout = node.getData() != null && node.getData().getConfig() != null
                ? (Integer) node.getData().getConfig().getOrDefault("timeout", 60) : 60;

        if (command == null || command.isBlank()) {
            return "Ошибка: команда не указана";
        }

        try {
            command = sanitizeCommand(command);
        } catch (SecurityException e) {
            if (webSocketHandler != null) {
                webSocketHandler.sendLog(schemaId, "error", "Command blocked: " + e.getMessage(), node.getId());
            }
            return "Blocked: " + e.getMessage();
        }

        try {
            // Split command into list form to avoid shell injection
            List<String> cmdParts = List.of(command.split("\\s+"));
            ProcessBuilder pb = new ProcessBuilder(cmdParts);
            if (workingDir != null && !workingDir.isBlank()) {
                pb.directory(new java.io.File(workingDir));
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try {
                boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return "Таймаут после " + timeout + " сек";
                }
                int exitCode = process.exitValue();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString();
                node.getData().setResult(output);
                node.getData().setConfig(Map.of("exitCode", exitCode));
                String result = output.isEmpty() ? "(пусто)" : output;
                if (exitCode == 0) {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "success", "Команда выполнена (exit " + exitCode + ")", node.getId());
                    }
                } else {
                    if (webSocketHandler != null) {
                        webSocketHandler.sendLog(schemaId, "error", "Команда завершена с ошибкой (exit " + exitCode + ")", node.getId());
                    }
                }
                return result.trim();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return "Прервано: " + e.getMessage();
            }
        } catch (Exception e) {
            log.error("Ошибка выполнения команды: {}", e.getMessage(), e);
            return "Ошибка: " + e.getMessage();
        }
    }

    public String sanitizeCommand(String command) {
        String lower = command.toLowerCase().trim();
        for (String blocked : BLOCKED_COMMAND_PATTERNS) {
            if (lower.contains(blocked.toLowerCase())) {
                throw new SecurityException("Command blocked: contains dangerous pattern '" + blocked + "'");
            }
        }
        if (lower.contains("$(rm ") || lower.contains("`rm ") || lower.contains("/dev/null >")) {
            throw new SecurityException("Command blocked: contains dangerous shell expansion");
        }
        return command;
    }
}
