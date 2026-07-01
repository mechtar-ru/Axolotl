package com.agent.orchestrator.llm;

/**
 * Response from an LLM provider call.
 * Separates the main text output from optional reasoning/thoughts.
 * finishReason captures the API finish_reason (stop, tool_calls, length, etc.).
 * Providers that don't support reasoning return reasoning=null.
 */
public record LlmResponse(String text, String reasoning, String finishReason) {

    public static LlmResponse textOnly(String text) {
        return new LlmResponse(text, null, null);
    }

    public static LlmResponse withReasoning(String text, String reasoning) {
        return new LlmResponse(text, reasoning, null);
    }

    public static LlmResponse full(String text, String reasoning, String finishReason) {
        return new LlmResponse(text, reasoning, finishReason);
    }

    public boolean hasReasoning() {
        return reasoning != null && !reasoning.isBlank();
    }
}
