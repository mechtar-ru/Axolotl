package com.agent.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.agent.orchestrator.llm.LlmResponse.textOnly;

/**
 * RLM Provider — wraps Python `rlm` package (Recursive Language Models).
 * Manages REPL environment with context variable, llm_query() recursion, FINAL_VAR() answer extraction.
 * @see <a href="https://github.com/alexzhang13/rlm">Reference</a>
 */
@Component
public class RlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(RlmProvider.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${axolotl.llm.rlm.enabled:false}")
    private boolean enabled;

    @Value("${axolotl.llm.rlm.python-path:python3}")
    private String pythonPath;

    @Value("${axolotl.llm.rlm.wrapper-script:}")
    private String wrapperScriptConfig;

    private String wrapperScript;

    @Value("${axolotl.llm.rlm.default-model:gpt-4o}")
    private String defaultModel;

    @Value("${axolotl.llm.rlm.timeout:3600}")
    private int timeoutSeconds;

    @Value("${axolotl.llm.rlm.sandbox-type:local}")
    private String sandboxType;

    @Value("${axolotl.llm.rlm.max-depth:5}")
    private int maxDepth;

    @PostConstruct
    public void init() {
        if (wrapperScriptConfig == null || wrapperScriptConfig.isBlank()) {
            wrapperScript = System.getProperty("user.home") + "/.local/bin/rlm_wrapper.py";
        } else {
            wrapperScript = wrapperScriptConfig;
        }
    }

    @Override
    public LlmResponse chat(String model, String systemPrompt, String userPrompt, Map<String, Object> config) {
        return chat(model, systemPrompt, userPrompt, config, null);
    }

    @Override
    public LlmResponse chat(String model, String systemPrompt, String userPrompt,
                       Map<String, Object> config, LlmUsage usage) {
        if (!enabled) {
            return textOnly("RLM provider not enabled. Set axolotl.llm.rlm.enabled=true");
        }

        String effectiveModel = (model != null && !model.isBlank() && !isProviderName(model))
                ? stripProviderPrefix(model) : defaultModel;

        String fullPrompt = buildRlmPrompt(systemPrompt, userPrompt);

        File promptFile = null;
        File outputFile = null;
        try {
            promptFile = File.createTempFile("rlm_prompt_", ".txt");
            outputFile = File.createTempFile("rlm_output_", ".json");
            Files.writeString(promptFile.toPath(), fullPrompt);

            List<String> command = new ArrayList<>();
            command.add(pythonPath);
            command.add(wrapperScript);
            command.add("--prompt-file");
            command.add(promptFile.getAbsolutePath());
            command.add("--output-file");
            command.add(outputFile.getAbsolutePath());
            command.add("--model");
            command.add(effectiveModel);
            command.add("--sandbox");
            command.add(sandboxType);
            command.add("--max-depth");
            command.add(String.valueOf(maxDepth));

            if (config != null) {
                if (config.containsKey("temperature")) {
                    command.add("--temperature");
                    command.add(String.valueOf(config.get("temperature")));
                }
                if (config.containsKey("max-tokens")) {
                    command.add("--max-tokens");
                    command.add(String.valueOf(config.get("max-tokens")));
                }
            }

            log.info("RLM request: model={}, sandbox={}, prompt length={}", effectiveModel, sandboxType, fullPrompt.length());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder processOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processOutput.append(line).append("\n");
                    log.debug("[RLM subprocess] {}", line);
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return textOnly("RLM timed out after " + timeoutSeconds + "s");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String error = "RLM subprocess failed (exit " + exitCode + "): " + processOutput;
                log.error(error);
                return textOnly(error);
            }

            if (outputFile.exists() && outputFile.length() > 0) {
                String jsonOutput = Files.readString(outputFile.toPath());
                try {
                    JsonNode root = objectMapper.readTree(jsonOutput);
                    String answer = root.path("answer").asText(null);
                    String resultError = root.path("error").asText(null);

                    if (resultError != null) {
                        log.error("RLM returned error: {}", resultError);
                        return textOnly("RLM error: " + resultError);
                    }
                    if (answer != null) {
                        log.info("RLM response: answer length={}", answer.length());
                        return textOnly(answer);
                    }

                    return textOnly(jsonOutput);
                } catch (Exception e) {
                    log.warn("Failed to parse RLM JSON output, returning raw: {}", e.getMessage());
                    return textOnly(jsonOutput);
                }
            }

            String output = processOutput.toString().trim();
            if (output.isEmpty()) {
                return textOnly("RLM produced no output");
            }
            return textOnly(output);

        } catch (Exception e) {
            String error = "RLM execution failed: " + e.getMessage();
            log.error(error, e);
            return textOnly(error);
        } finally {
            if (promptFile != null) promptFile.delete();
            if (outputFile != null) outputFile.delete();
        }
    }

    @Override
    public boolean isAvailable() {
        if (!enabled) return false;

        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, "-c",
                    "import rlm; print('RLM available')");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            log.warn("RLM availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getName() {
        return "rlm";
    }

    @Override
    public List<String> listModels() {
        return List.of(
                "gpt-4o", "gpt-4o-mini", "gpt-4-turbo",
                "claude-sonnet-4-20250514", "claude-haiku-4-20250514",
                "deepseek-chat", "local"
        );
    }

    @Override
    public String getBaseUrl() {
        return "rlm://" + sandboxType;
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    @Override
    public LlmResponse streamingChat(String model, String systemPrompt, String userPrompt,
                                Map<String, Object> config, java.util.function.Consumer<String> onToken) {
        LlmResponse response = chat(model, systemPrompt, userPrompt, config);
        onToken.accept(response.text());
        return response;
    }

    private String buildRlmPrompt(String systemPrompt, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("# System Instructions\n\n");
            sb.append(systemPrompt).append("\n\n");
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("# User Query\n\n");
            sb.append(userPrompt);
        }
        return sb.toString();
    }

    private boolean isProviderName(String model) {
        return "rlm".equalsIgnoreCase(model) || "recursive".equalsIgnoreCase(model);
    }

    private String stripProviderPrefix(String model) {
        int colon = model.indexOf(':');
        return colon > 0 ? model.substring(colon + 1) : model;
    }
}
