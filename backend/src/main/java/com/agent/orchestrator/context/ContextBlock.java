package com.agent.orchestrator.context;

import java.util.Objects;

/**
 * A named, prioritized piece of context for assembling the agent's system prompt.
 * Each block has a priority that determines whether it is included
 * when the total token budget is exceeded.
 */
public record ContextBlock(
    String name,
    String content,
    ContextPriority priority,
    int estimatedTokens
) {
    public ContextBlock {
        Objects.requireNonNull(name, "ContextBlock name must not be null");
        Objects.requireNonNull(content, "ContextBlock content must not be null");
        Objects.requireNonNull(priority, "ContextBlock priority must not be null");
    }

    /**
     * Create a block with auto-calculated token estimate.
     */
    public ContextBlock(String name, String content, ContextPriority priority) {
        this(name, content, priority, TokenCounter.estimate(content));
    }

    /**
     * Return a new block truncated to approximately maxTokens tokens.
     * Returns itself if already within budget.
     */
    public ContextBlock truncateTo(int maxTokens) {
        if (estimatedTokens <= maxTokens || maxTokens <= 0) return this;
        String truncated = TokenCounter.truncateTo(content, maxTokens);
        return new ContextBlock(name, truncated, priority, TokenCounter.estimate(truncated));
    }

    /**
     * Return true if this block has no substantive content.
     */
    public boolean isEmpty() {
        return content.isBlank();
    }
}
