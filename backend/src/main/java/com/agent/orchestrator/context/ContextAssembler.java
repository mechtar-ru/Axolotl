package com.agent.orchestrator.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Assembles a prioritized list of ContextBlock records into a system prompt
 * that fits within a configurable token budget.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Sort blocks by priority order ascending (CRITICAL first)</li>
 *   <li>Reserve budget for CRITICAL blocks (always included, never truncated)</li>
 *   <li>Iterate remaining blocks by priority: include full or truncated if budget remains</li>
 *   <li>EXPERIMENTAL blocks are skipped entirely when budget is tight</li>
 *   <li>Return assembled prompt + detailed stats for observability</li>
 * </ol>
 */
@Component
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    /** Default token budget (0 = disabled, no limit) */
    public static final int DEFAULT_BUDGET_TOKENS = 0;

    /**
     * Assemble context blocks into a system prompt within the given token budget.
     * <p>
     * When {@code totalBudget <= 0}, all blocks are included fully in their original
     * list order (no priority sorting, no truncation). Only the total token count
     * is computed for observability.
     * <p>
     * When {@code totalBudget > 0}, blocks are sorted by priority and lower-priority
     * blocks are truncated or skipped to fit within the budget.
     *
     * @param blocks       context blocks (order preserved when budget disabled)
     * @param totalBudget  maximum total tokens for non-CRITICAL blocks combined
     *                     when budget is enabled; ≤0 means disabled (no limit)
     * @return assembly result containing the final text and block-level stats
     */
    public AssemblyResult assemble(List<ContextBlock> blocks, int totalBudget) {
        if (blocks == null || blocks.isEmpty()) {
            return AssemblyResult.EMPTY;
        }

        // Budget disabled (≤0): include all blocks in order, no truncation
        if (totalBudget <= 0) {
            StringBuilder sb = new StringBuilder();
            List<BlockStat> stats = new ArrayList<>();
            int totalTokens = 0;
            for (ContextBlock block : blocks) {
                if (block.isEmpty()) continue;
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(block.content());
                totalTokens += block.estimatedTokens();
                stats.add(BlockStat.included(block.name(), block.estimatedTokens(), block.priority()));
            }
            return new AssemblyResult(sb.toString(), totalTokens, 0, 0, stats);
        }

        int effectiveBudget = totalBudget;
        List<ContextBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingInt(b -> b.priority().getOrder()));

        StringBuilder finalPrompt = new StringBuilder();
        List<ContextBlock> criticalBlocks = new ArrayList<>();
        List<ContextBlock> remaining = new ArrayList<>();
        List<BlockStat> stats = new ArrayList<>();

        // Split into critical vs others
        for (ContextBlock block : sorted) {
            if (block.priority() == ContextPriority.CRITICAL) {
                criticalBlocks.add(block);
            } else {
                remaining.add(block);
            }
        }

        // Always include critical blocks fully
        int criticalTokens = 0;
        for (ContextBlock cb : criticalBlocks) {
            if (cb.isEmpty()) continue;
            if (!finalPrompt.isEmpty()) finalPrompt.append("\n\n");
            finalPrompt.append(cb.content());
            criticalTokens += cb.estimatedTokens();
            stats.add(BlockStat.included(cb.name(), cb.estimatedTokens(), cb.priority()));
        }

        // Distribute remaining budget across priority levels
        int availableBudget = effectiveBudget;
        for (ContextBlock block : remaining) {
            if (block.isEmpty()) continue;
            if (block.priority() == ContextPriority.EXPERIMENTAL && availableBudget < block.estimatedTokens()) {
                stats.add(BlockStat.skipped(block.name(), block.estimatedTokens(), block.priority()));
                log.debug("Skipped experimental context block '{}' ({} tokens) — budget exhausted",
                        block.name(), block.estimatedTokens());
                continue;
            }
            if (availableBudget <= 0) {
                stats.add(BlockStat.skipped(block.name(), block.estimatedTokens(), block.priority()));
                continue;
            }

            ContextBlock toAdd;
            boolean truncated = block.estimatedTokens() > availableBudget;
            if (truncated) {
                toAdd = block.truncateTo(availableBudget);
                log.info("Truncated context block '{}' from {} to ~{} tokens",
                        block.name(), block.estimatedTokens(), availableBudget);
            } else {
                toAdd = block;
            }

            if (!finalPrompt.isEmpty()) finalPrompt.append("\n\n");
            finalPrompt.append(toAdd.content());
            availableBudget -= toAdd.estimatedTokens();
            if (truncated) {
                stats.add(BlockStat.truncated(toAdd.name(), block.estimatedTokens(), toAdd.priority(), toAdd.estimatedTokens()));
            } else {
                stats.add(BlockStat.included(toAdd.name(), toAdd.estimatedTokens(), toAdd.priority()));
            }
        }

        int totalUsed = criticalTokens + (effectiveBudget - availableBudget);
        if (log.isDebugEnabled()) {
            log.debug("Context assembly: {} blocks, {} critical + {} budget tokens used out of {}",
                    blocks.size(), criticalTokens, totalUsed - criticalTokens, effectiveBudget);
        }

        return new AssemblyResult(finalPrompt.toString(), totalUsed, criticalTokens, effectiveBudget, stats);
    }

    /**
     * Convenience: assemble a single critical block + prioritized list.
     */
    public AssemblyResult assembleWithCritical(ContextBlock critical, List<ContextBlock> others, int budget) {
        List<ContextBlock> all = new ArrayList<>();
        all.add(critical);
        if (others != null) all.addAll(others);
        return assemble(all, budget);
    }

    // ─── Result types ───

    public record BlockStat(
            String name,
            int tokens,
            ContextPriority priority,
            boolean included,
            boolean skipped,
            boolean truncated
    ) {
        public static BlockStat included(String name, int tokens, ContextPriority priority) {
            return new BlockStat(name, tokens, priority, true, false, false);
        }

        public static BlockStat truncated(String name, int originalTokens, ContextPriority priority, int newTokens) {
            return new BlockStat(name, newTokens, priority, true, false, true);
        }

        public static BlockStat skipped(String name, int tokens, ContextPriority priority) {
            return new BlockStat(name, tokens, priority, false, true, false);
        }
    }

    public record AssemblyResult(
            String text,
            int totalTokens,
            int criticalTokens,
            int budgetTokens,
            List<BlockStat> stats
    ) {
        public static final AssemblyResult EMPTY = new AssemblyResult("", 0, 0, 0, List.of());

        public boolean hasBudgetRemaining() {
            return (totalTokens - criticalTokens) < budgetTokens;
        }
    }
}
