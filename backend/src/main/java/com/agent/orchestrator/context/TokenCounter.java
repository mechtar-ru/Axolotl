package com.agent.orchestrator.context;

/**
 * Rough token estimator for system prompt text.
 * Uses conservative ~3.5 chars per token heuristic
 * suitable for mixed English/Russian text.
 */
public class TokenCounter {

    /** Default chars-per-token ratio for mixed text */
    public static final double CHARS_PER_TOKEN = 3.5;

    private TokenCounter() {}

    /**
     * Estimate token count for a string.
     * Returns 0 for null/blank input.
     */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }

    /**
     * Estimate maximum characters for a given token budget.
     */
    public static int maxCharsForTokens(int tokens) {
        if (tokens <= 0) return 0;
        return (int) (tokens * CHARS_PER_TOKEN);
    }

    /**
     * Truncate text to approximately fit within maxTokens,
     * appending a truncation notice.
     */
    public static String truncateTo(String text, int maxTokens) {
        if (text == null) return "";
        if (maxTokens <= 0) return "";
        int estimated = estimate(text);
        if (estimated <= maxTokens) return text;

        int maxChars = maxCharsForTokens(maxTokens);
        String suffix = "\n[... truncated from ~" + estimated + " tokens]";
        int truncateAt = Math.max(0, Math.min(text.length(), maxChars));
        if (truncateAt + suffix.length() > text.length()) {
            return text; // not worth truncating
        }
        return text.substring(0, truncateAt) + suffix;
    }
}
