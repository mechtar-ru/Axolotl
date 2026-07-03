package com.agent.orchestrator.llm;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of a streaming LLM call with structured tool calls and finish reason.
 * Used by the AI SDK-style streaming agent loop.
 */
public record StreamingResult(
    String text,
    List<Map<String, Object>> toolCalls,
    String finishReason,
    String reasoning,
    int totalTokens
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isStop() {
        return "stop".equals(finishReason);
    }

    public boolean isToolCalls() {
        return "tool_calls".equals(finishReason);
    }
}
