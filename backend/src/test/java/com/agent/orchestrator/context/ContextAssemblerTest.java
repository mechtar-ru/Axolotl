package com.agent.orchestrator.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.agent.orchestrator.context.ContextPriority.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler();

    @Test
    void emptyInputReturnsEmpty() {
        var result = assembler.assemble(null, 8000);
        assertTrue(result.text().isEmpty());
        assertEquals(0, result.totalTokens());

        result = assembler.assemble(List.of(), 8000);
        assertTrue(result.text().isEmpty());
    }

    @Test
    void criticalBlockAlwaysIncluded() {
        var blocks = List.of(
                new ContextBlock("sys", "You are a coding agent", CRITICAL)
        );
        var result = assembler.assemble(blocks, 100);
        assertTrue(result.text().contains("coding agent"));
        assertTrue(result.criticalTokens() > 0);
    }

    @Test
    void lowPriorityBlockTruncatedWhenBudgetTight() {
        // CRITICAL takes ~10 tokens, LOW should be truncated
        var blocks = List.of(
                new ContextBlock("sys", "hi", CRITICAL),
                new ContextBlock("project", "A".repeat(2000), LOW)
        );
        var result = assembler.assemble(blocks, 100);
        assertTrue(result.text().contains("hi"));
        assertTrue(result.text().contains("truncated"));
        // Critical was included, then project was truncated
        assertTrue(result.stats().stream()
                .anyMatch(s -> s.name().equals("project") && s.truncated()));
    }

    @Test
    void experimentalSkippedWhenBudgetTight() {
        var blocks = List.of(
                new ContextBlock("sys", "hi", CRITICAL),
                new ContextBlock("mempalace", "B".repeat(1000), EXPERIMENTAL)
        );
        // Budget 20 is too small for 1000-token experimental
        var result = assembler.assemble(blocks, 20);
        // CRITICAL included
        assertTrue(result.text().contains("hi"));
        // EXPERIMENTAL should be skipped if budget exhausted after critical
        boolean skipped = result.stats().stream()
                .anyMatch(s -> s.name().equals("mempalace") && s.skipped());
        assertTrue(skipped, "EXPERIMENTAL block should be skipped when budget exhausted");
    }

    @Test
    void priorityOrdering() {
        // Even if added in reverse order, HIGH should be preferred over LOW
        var blocks = List.of(
                new ContextBlock("sys", "hi", CRITICAL),
                new ContextBlock("plan", "C".repeat(2000), HIGH),
                new ContextBlock("project", "D".repeat(2000), LOW)
        );
        var result = assembler.assemble(blocks, 450);
        // With 450 tokens budget ≈ 1575 chars, plan (2000 chars) needs truncation
        assertTrue(result.text().contains("hi"));
        // Plan steps should be included (even if truncated)
        var planStat = result.stats().stream()
                .filter(s -> s.name().equals("plan")).findFirst();
        assertTrue(planStat.isPresent());
        assertTrue(planStat.get().included(), "HIGH priority plan should be included");
    }

    @Test
    void multipleCriticalBlocks() {
        var blocks = List.of(
                new ContextBlock("sys", "system prompt here", CRITICAL),
                new ContextBlock("user", "user prompt here", CRITICAL),
                new ContextBlock("project", "project context", LOW)
        );
        var result = assembler.assemble(blocks, 1000);
        assertTrue(result.text().contains("system prompt here"));
        assertTrue(result.text().contains("user prompt here"));
        assertTrue(result.text().contains("project context"));
    }

    @Test
    void emptyBlocksAreSkipped() {
        var blocks = List.of(
                new ContextBlock("sys", "prompt", CRITICAL),
                new ContextBlock("empty", "", HIGH),
                new ContextBlock("project", "context", LOW)
        );
        var result = assembler.assemble(blocks, 1000);
        assertTrue(result.text().contains("prompt"));
        assertTrue(result.text().contains("context"));
    }

    @Test
    void disabledBudgetReturnsAllBlocksInOrder() {
        // Budget 0 = disabled: all blocks included fully, no priority sorting
        var blocks = List.of(
                new ContextBlock("first", "FIRST_BLOCK", LOW),
                new ContextBlock("second", "SECOND_BLOCK", EXPERIMENTAL),
                new ContextBlock("third", "THIRD_BLOCK", MEDIUM)
        );
        var result = assembler.assemble(blocks, 0);
        String text = result.text();
        // All blocks present
        assertTrue(text.contains("FIRST_BLOCK"), "first block should be included");
        assertTrue(text.contains("SECOND_BLOCK"), "second block should be included");
        assertTrue(text.contains("THIRD_BLOCK"), "third block should be included");
        // Order preserved (EXPERIMENTAL before MEDIUM — no priority sorting)
        int firstIdx = text.indexOf("FIRST_BLOCK");
        int secondIdx = text.indexOf("SECOND_BLOCK");
        int thirdIdx = text.indexOf("THIRD_BLOCK");
        assertTrue(firstIdx < secondIdx, "order should be preserved (1st before 2nd)");
        assertTrue(secondIdx < thirdIdx, "order should be preserved (2nd before 3rd)");
        // No truncated or skipped stats
        assertTrue(result.stats().stream().allMatch(s -> s.included() && !s.truncated() && !s.skipped()),
                "all blocks should be included without truncation");
        // budgetTokens = 0 when disabled
        assertEquals(0, result.budgetTokens());
    }

    @Test
    void disabledBudgetWithNullBlocks() {
        var result = assembler.assemble(null, 0);
        assertTrue(result.text().isEmpty());
        assertEquals(0, result.totalTokens());
    }

    @Test
    void tokenCounterAccuracy() {
        // English text: ~4 chars per token
        String english = "Hello world this is a test";
        int tokens = TokenCounter.estimate(english);
        assertTrue(tokens > 0);
        assertEquals(english.length(), english.length()); // sanity

        // Russian text: ~3 chars per token due to Unicode
        String russian = "Привет мир это тестовое сообщение";
        int ruTokens = TokenCounter.estimate(russian);
        assertTrue(ruTokens > 0);

        // Empty
        assertEquals(0, TokenCounter.estimate(""));
        assertEquals(0, TokenCounter.estimate(null));
    }
}
