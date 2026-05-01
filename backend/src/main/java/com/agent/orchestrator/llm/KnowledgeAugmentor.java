package com.agent.orchestrator.llm;

/**
 * Interface for components that augment agent prompts with external knowledge.
 * Implementations can be enabled/disabled via configuration.
 */
public interface KnowledgeAugmentor {

    /**
     * Whether this augmentor is enabled and should be called.
     */
    boolean isEnabled();

    /**
     * Return knowledge block to inject into agent prompt, or empty string if none.
     * @param prompt the agent prompt to augment
     * @return formatted knowledge block (e.g., "## Relevant Skills\n...")
     */
    String getKnowledgeForPrompt(String prompt);
}
