package com.agent.orchestrator.analytics;

import java.util.*;

/**
 * Detects recurring sub-sequences in workflow execution history.
 * 
 * Output format for NodeFactory: List<Pattern> where each Pattern has:
 * - sequence: List<String> (node types in order)
 * - frequency: int (how many times it occurred)
 * - confidence: double (frequency / total workflows)
 */


public class PatternDetector {
    private int minFrequency = 3;
    private int maxPatternLength = 5;

    public PatternDetector() {}

    public PatternDetector(int minFrequency, int maxPatternLength) {
        this.minFrequency = minFrequency;
        this.maxPatternLength = maxPatternLength;
    }

    /**
     * Detects patterns from workflow execution sequences.
     * Each sequence is a list of node types in execution order.
     */
    public List<Pattern> detectPatterns(List<List<String>> executionSequences) {
        Map<String, Pattern> patternMap = new HashMap<>();

        for (List<String> sequence : executionSequences) {
            // Extract all n-grams from length 2 to maxPatternLength
            for (int n = 2; n <= Math.min(sequence.size(), maxPatternLength); n++) {
                for (int i = 0; i <= sequence.size() - n; i++) {
                    List<String> subSequence = sequence.subList(i, i + n);
                    String key = subSequence.toString();

                    patternMap.computeIfAbsent(key, k -> new Pattern(subSequence))
                              .incrementFrequency();
                }
            }
        }

        // Filter by minimum frequency and convert to list
        List<Pattern> result = new ArrayList<>();
        for (Pattern p : patternMap.values()) {
            if (p.getFrequency() >= minFrequency) {
                p.setConfidence((double) p.getFrequency() / executionSequences.size());
                result.add(p);
            }
        }

        return result;
    }

    public int getMinFrequency() { return minFrequency; }
    public void setMinFrequency(int minFrequency) { this.minFrequency = minFrequency; }
    public int getMaxPatternLength() { return maxPatternLength; }
    public void setMaxPatternLength(int maxPatternLength) { this.maxPatternLength = maxPatternLength; }

    /**
     * Represents a detected pattern.
     */
    public static class Pattern {
        private final List<String> sequence;
        private int frequency;
        private double confidence;

        public Pattern(List<String> sequence) {
            this.sequence = new ArrayList<>(sequence);
            this.frequency = 0;
            this.confidence = 0.0;
        }

        public List<String> getSequence() { return new ArrayList<>(sequence); }
        public int getFrequency() { return frequency; }
        public double getConfidence() { return confidence; }

        void incrementFrequency() { frequency++; }
        void setConfidence(double confidence) { this.confidence = confidence; }

        @Override
        public String toString() {
            return "Pattern{sequence=" + sequence + ", frequency=" + frequency + ", confidence=" + confidence + "}";
        }
    }
}
