package com.agent.orchestrator.context;

/**
 * Rough token estimator for system prompt text.
 * Uses conservative ~3.5 chars per token heuristic
 * suitable for mixed English/Russian text.
 * <p>
 * Can be configured with a custom chars-per-token ratio via constructor.
 */
public class TokenCounter {

    /** Default chars-per-token ratio for mixed text */
    public static final double CHARS_PER_TOKEN = 3.5;

    private final double charsPerToken;

    /**
     * Create a TokenCounter with the default chars-per-token ratio (3.5).
     */
    public TokenCounter() {
        this(CHARS_PER_TOKEN);
    }

    /**
     * Create a TokenCounter with a custom chars-per-token ratio.
     *
     * @param charsPerToken chars-per-token ratio (e.g. 3.5 for mixed text, 4.0 for English-only)
     */
    public TokenCounter(double charsPerToken) {
        this.charsPerToken = charsPerToken;
    }

    /**
     * Static convenience: estimate token count using default ratio.
     * Returns 0 for null/blank input.
     */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }

    /**
     * Instance: estimate token count using configured ratio.
     * Returns 0 for null/blank input.
     */
    public int estimateConfigured(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / charsPerToken));
    }

    /**
     * Static convenience: estimate max chars using default ratio.
     */
    public static int maxCharsForTokens(int tokens) {
        if (tokens <= 0) return 0;
        return (int) (tokens * CHARS_PER_TOKEN);
    }

    /**
     * Instance: estimate max chars using configured ratio.
     */
    public int maxCharsFor(int tokens) {
        if (tokens <= 0) return 0;
        return (int) (tokens * charsPerToken);
    }

    /**
     * Static convenience: truncate text using default ratio.
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

    /**
     * Instance: truncate text using configured ratio.
     */
    public String truncateConfigured(String text, int maxTokens) {
        if (text == null) return "";
        if (maxTokens <= 0) return "";
        int estimated = estimateConfigured(text);
        if (estimated <= maxTokens) return text;

        int maxChars = maxCharsFor(maxTokens);
        String suffix = "\n[... truncated from ~" + estimated + " tokens]";
        int truncateAt = Math.max(0, Math.min(text.length(), maxChars));
        if (truncateAt + suffix.length() > text.length()) {
            return text; // not worth truncating
        }
        return text.substring(0, truncateAt) + suffix;
    }
}
