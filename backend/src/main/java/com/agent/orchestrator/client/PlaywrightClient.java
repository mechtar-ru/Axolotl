package com.agent.orchestrator.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component
public class PlaywrightClient {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightClient.class);
    private static final Path MCP_SCRIPT = Paths.get("/Users/evgenijtihomirov/git/Axolotl/Axolotl/frontend/playwright_mcp.cjs");
    private static final int TIMEOUT_MS = 30000;

    private Process currentProcess;

    public boolean isAvailable() {
        return Files.exists(MCP_SCRIPT);
    }

    public void start() throws IOException {
        if (currentProcess != null && currentProcess.isAlive()) {
            return;
        }
        
        ProcessBuilder pb = new ProcessBuilder("node", MCP_SCRIPT.toString());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        currentProcess = pb.start();
        log.info("Playwright MCP started");
    }

    public String executeTool(String toolName, Map<String, Object> args) throws IOException {
        if (currentProcess == null || !currentProcess.isAlive()) {
            start();
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tool", toolName);
        request.put("args", args);

        String requestJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request);
        
        try {
            OutputStream os = currentProcess.getOutputStream();
            os.write(requestJson.getBytes());
            os.write("\n".getBytes());
            os.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
            String line = reader.readLine();
            
            if (line == null) {
                return "{\"ok\": false, \"data\": \"MCP server closed\"}";
            }
            return line;
        } catch (IOException e) {
            log.error("Playwright tool error: {}", e.getMessage(), e);
            try {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("ok", false);
                err.put("data", e.getMessage());
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(err);
            } catch (Exception ex) {
                return "{\"ok\": false, \"data\": \"Unknown error\"}";
            }
        }
    }

    public void stop() {
        if (currentProcess != null) {
            currentProcess.destroy();
            currentProcess = null;
            log.info("Playwright MCP stopped");
        }
    }
}