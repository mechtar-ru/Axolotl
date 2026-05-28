package com.agent.orchestrator.llm;

/**
 * Token usage information from an LLM API call.
 * Created by the caller and passed through as a mutable container;
 * each provider fills it in after receiving the API response.
 */
public class LlmUsage {
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;

    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public void add(LlmUsage other) {
        this.inputTokens += other.inputTokens;
        this.outputTokens += other.outputTokens;
        this.totalTokens += other.totalTokens;
    }
}
