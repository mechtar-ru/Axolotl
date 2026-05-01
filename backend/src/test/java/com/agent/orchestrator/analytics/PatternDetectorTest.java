package com.agent.orchestrator.analytics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Unit tests for PatternDetector.
 */
class PatternDetectorTest {

    @Test
    void shouldDetectSimplePattern() {
        PatternDetector detector = new PatternDetector(2, 3);
        
        List<List<String>> sequences = Arrays.asList(
            Arrays.asList("source", "agent", "output"),
            Arrays.asList("source", "agent", "output"),
            Arrays.asList("source", "agent", "output")
        );
        
        List<PatternDetector.Pattern> patterns = detector.detectPatterns(sequences);
        
        // [source, agent, output] should be detected with frequency >= 2
        assertTrue(patterns.size() > 0);
        boolean found = patterns.stream()
            .anyMatch(p -> p.getSequence().equals(Arrays.asList("source", "agent", "output"))
                        && p.getFrequency() >= 2);
        assertTrue(found);
    }

    @Test
    void shouldRespectMinFrequencyThreshold() {
        PatternDetector detector = new PatternDetector(3, 3);
        
        List<List<String>> sequences = Arrays.asList(
            Arrays.asList("source", "agent", "output"), // appears 2 times
            Arrays.asList("source", "agent", "output")
        );
        
        List<PatternDetector.Pattern> patterns = detector.detectPatterns(sequences);
        
        // No patterns should be returned (frequency 2 < threshold 3)
        assertTrue(patterns.isEmpty());
    }

    @Test
    void shouldDetectMultiplePatterns() {
        PatternDetector detector = new PatternDetector(2, 4);
        
        List<List<String>> sequences = Arrays.asList(
            Arrays.asList("source", "agent", "output"),
            Arrays.asList("source", "agent", "output"),
            Arrays.asList("agent", "output", "sink")
        );
        
        List<PatternDetector.Pattern> patterns = detector.detectPatterns(sequences);
        
        // Should detect [source, agent] (2x), [agent, output] (3x), [source, agent, output] (2x)
        assertTrue(patterns.size() >= 2);
    }

    @Test
    void shouldCalculateConfidence() {
        PatternDetector detector = new PatternDetector(2, 3);
        
        List<List<String>> sequences = Arrays.asList(
            Arrays.asList("source", "agent", "output"),
            Arrays.asList("source", "agent", "output"),
            Arrays.asList("source", "agent", "output")
        );
        
        List<PatternDetector.Pattern> patterns = detector.detectPatterns(sequences);
        
        PatternDetector.Pattern pattern = patterns.stream()
            .filter(p -> p.getSequence().equals(Arrays.asList("source", "agent", "output")))
            .findFirst()
            .orElse(null);
        
        assertNotNull(pattern);
        assertEquals(1.0, pattern.getConfidence(), 0.001); // 3/3 = 1.0
    }

    @Test
    void shouldRespectMaxPatternLength() {
        PatternDetector detector = new PatternDetector(2, 2); // max pattern length = 2
        
        List<List<String>> sequences = Arrays.asList(
            Arrays.asList("a", "b", "c", "d"),
            Arrays.asList("a", "b", "c", "d")
        );
        
        List<PatternDetector.Pattern> patterns = detector.detectPatterns(sequences);
        
        // Should NOT detect [a, b, c, d] because maxPatternLength is 2
        boolean foundLong = patterns.stream()
            .anyMatch(p -> p.getSequence().size() > 2);
        assertFalse(foundLong);
    }
}
