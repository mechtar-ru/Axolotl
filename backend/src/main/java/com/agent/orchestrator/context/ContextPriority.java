package com.agent.orchestrator.context;

/**
 * Priority levels for context blocks in the agent's system prompt.
 * Lower order value = higher priority (never skipped).
 */
public enum ContextPriority {
    CRITICAL(0),       // systemPrompt, userPrompt — always included, never truncated
    HIGH(1),           // plan steps context
    MEDIUM(2),         // predecessor results, sourceData
    LOW(3),            // project context (file tree + session history)
    EXPERIMENTAL(4);   // mempalace graph context — first to drop

    private final int order;

    ContextPriority(int order) {
        this.order = order;
    }

    public int getOrder() {
        return order;
    }
}
