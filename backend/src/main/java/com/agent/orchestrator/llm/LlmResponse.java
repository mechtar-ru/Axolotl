package com.agent.orchestrator.llm;

/**
 * Response from an LLM provider call.
 * Separates the main text output from optional reasoning/thoughts.
 * Providers that don't support reasoning return reasoning=null.
 */
public record LlmResponse(String text, String reasoning) {

    public static LlmResponse textOnly(String text) {
        return new LlmResponse(text, null);
    }

    public static LlmResponse withReasoning(String text, String reasoning) {
        return new LlmResponse(text, reasoning);
    }

    public boolean hasReasoning() {
        return reasoning != null && !reasoning.isBlank();
    }
}
