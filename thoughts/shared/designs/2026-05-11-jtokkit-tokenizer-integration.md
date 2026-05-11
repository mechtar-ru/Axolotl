---
date: 2026-05-11
topic: "jtokkit Real Tokenizer Integration for ContextCurationService"
status: draft
---

## Problem Statement

`ContextCurationService.countTokens()` uses a heuristic regex tokenizer that splits on whitespace and punctuation, then assigns `ceil(len/4)` tokens per segment. This systematically overcounts tokens by 20-60% compared to real BPE tokenizers (GPT-4 cl100k_base, Llama 3). The `Math.max(heuristic, charBasedEstimate)` tiebreaker at line 132 compounds the overcount by taking the worst of two estimators.

The result: the context curation system under-utilizes the token budget by 20-60%, meaning the curated prompt is significantly shorter than it could be while still fitting within the specified budget.

Additionally, `TOKEN_PER_CHAR = 0.33` and `CODE_SAFETY_MARGIN = 1.2` are hardcoded constants (lines 20-23) that were manually calibrated once — they diverge as the codebase evolves, with no feedback loop.

All 7 LLM providers (OpenAI, Anthropic, DeepSeek, Zen, etc.) send raw text with zero client-side token counting. The `usage.total_tokens` values they return are logged but immediately discarded.

## Constraints

- **No behavioral regression.** Existing callers of `GraphController.curate()` and `ContextCurationService.curateForQuery()` must continue working with identical input/output contracts — only the token count accuracy changes.
- **Minimal dependency surface.** The backend currently has 20+ dependencies. Adding jtokkit is acceptable but should be the only new dependency.
- **No model-specific assumptions.** The curation service is model-agnostic — the prompt may be consumed by any LLM. Use cl100k_base (GPT-4 encoding) as the default, which is the most widely compatible BPE encoding.
- **Graceful degradation.** If jtokkit fails at runtime, fall back to the existing heuristic to ensure zero downtime.

## Approach

**Chosen: Replace the entire heuristic stack with jtokkit (`com.knuddels:jtokkit`).**

### Why jtokkit over alternatives

| Option | Error | Effort | Risk | Verdict |
|---|---|---|---|---|
| **jtokkit** (real BPE) | ~2% | Low (1 dep + 12 lines) | Low | **Chosen** |
| Recalibrate constants | ~30% | Low (edit constants) | Low | Wrong by design — still guessing |
| Adaptive EMA from API | ~5% | Medium (wire 7 providers) | Medium | Second-order effect — fix root cause first |
| Keep current heuristic | ~40% | Zero | None | Unacceptable waste |

jtokkit is the Java tiktoken binding — same tokenizer that OpenAI uses server-side. It's correct by construction, not by calibration.

### What we remove

- `TOKEN_PER_CHAR` constant (line 21)
- `CODE_SAFETY_MARGIN` constant (line 23)
- `maxChars` pre-allocation computation (lines 43-46)
- `Math.max(estimatedTokens, charBasedEstimate)` tiebreaker (line 132)
- The entire `charBasedEstimate` variable and its usage (lines 130-132, 148-150)
- The char-based trim logic tied to `maxChars` (lines 108-117)

### What we replace

- `countTokens()` body (lines 213-225): replace regex heuristic with `Encoding.forModel("gpt-4").countTokens(text)`

### What we keep

- The entity ranking and relevance scoring logic (unchanged)
- The `CurationResult` record shape (unchanged — only accuracy of `tokenCount` field improves)
- The trim-on-exceed logic (line 135-158) — still needed for the per-entity append check
- The existing tests (all continue passing with more accurate token counts)

## Architecture

```
Before (current):

  budget ──→ TOKEN_PER_CHAR(0.33) ──→ maxChars ──→ maxChars safety margin (÷1.2)
                                                         │
                                              append entities until currentChars ≥ maxChars
                                                         │
                                              countTokens() [regex: split on \W, ceil(len/4)]
                                                         │
                                              Math.max(countTokens, currentChars × 0.33)
                                                         │
                                              if > budget: trim last entity, recount, repeat
                                                         │
                                                    estimatedTokens

After (jtokkit):

  budget ──→ jtokkit.countTokens(formattedEntity) ──→ runningTokenTotal += entityTokens
                                                         │
                                              if runningTokenTotal ≥ budget: stop, rollback
                                                         │
                                              jtokkit.countTokens(finalPrompt) for sanity
                                                         │
                                                    estimatedTokens (real)
```

The key architectural change: **char-based pre-allocation is eliminated entirely.** We count real tokens as we build the prompt, not after guessing a char limit. This makes the append loop single-pass instead of append-then-possibly-trim-and-recount.

## Components

### 1. `pom.xml` — Dependency addition

Add `com.knuddels:jtokkit:1.1.0` to the `<dependencies>` section alongside existing utilities (java-dotenv, jackson, javaparser).

### 2. `ContextCurationService.java` — Core changes

| Location | Change | Detail |
|---|---|---|
| Lines 20-23 | Remove `TOKEN_PER_CHAR`, `CODE_SAFETY_MARGIN` | No longer needed |
| Lines 43-46 | Remove `maxChars` computation | Budget enforcement moves to jtokkit |
| Lines 108-117 | Replace char-based trim loop | Instead of `currentChars >= maxChars`, check `runningTokens + entityTokens > budget` |
| Lines 129-132 | Remove `estimatedTokens` + `Math.max` | Single `countTokens()` call after assembly |
| Lines 213-225 | Replace `countTokens()` body | `Encoding.forModel("gpt-4").countTokens(text)` with heuristic fallback |
| Lines 240-265 | No change needed | `formatEntity()` produces same output |

### 3. `MetricsService.java` — New token metrics

Add two Prometheus counters:
- `axolotl.token.estimated.total` — cumulative token count across all curation calls
- `axolotl.token.estimated.per_call` — distribution of token counts per call (histogram)

These track real token usage for observability and future budget tuning.

### 4. `ContextCurationServiceTest.java` — Test updates

- `testCountTokens_simpleText()` — now asserts exact count for known string (e.g., "hello world" = 2 tokens in cl100k_base)
- `testCountTokens_javaCode()` — asserts exact count for known Java snippet
- New `testJtokkitAccuracy()` — validates against a known reference string with a documented token count
- New `testJtokkitFallback()` — verifies heuristic fallback when encoding fails (inject encoding error)
- Existing `testCurateForQuery_returnsWithinBudget()` — continues passing with accurate counts

## Data Flow

```
GraphController.curate(query, budget, recentHashes)
  │
  ▼
ContextCurationService.curateForQuery(query, budget, recentHashes)
  │
  ├─── Query Neo4j: find matching classes + methods (unchanged)
  │
  ├─── Rank + boost recent hashes (unchanged)
  │
  ├─── Sort by score (unchanged)
  │
  ├─── Initialize: StringBuilder prompt, int runningTokens = 0
  │
  ├─── For each entity in order:
  │      ├── formatEntity(e) → entityText
  │      ├── int entityTokens = jtokkit.countTokens(entityText)
  │      ├── if (runningTokens + entityTokens > budget) → break
  │      ├── prompt.append(entityText)
  │      ├── runningTokens += entityTokens
  │      └── track hashes
  │
  ├─── int totalTokens = jtokkit.countTokens(prompt.toString())
  │
  ├─── Record MetricsService metrics
  │
  └─── Return CurationResult(prompt, totalTokens, hashes, strategy)
```

## Error Handling

| Scenario | Behavior | Code |
|---|---|---|
| jtokkit `Encoding.forModel()` throws | Log error, fall back to old heuristic `countTokens()` | Wrap in try-catch around encoding initialization |
| jtokkit `countTokens()` throws on specific text | Log warning, treat entity as 0 tokens (skipped) | Per-entity try-catch, entity is excluded |
| jtokkit dependency missing at startup | Spring context fails to load — fails fast | Standard Maven dependency resolution |
| Token budget very small (< 10 tokens) | jtokkit correctly counts, no special handling | No change needed |

The heuristic fallback lives as `private static int countTokensFallback(String text)` — the exact same regex logic from the current implementation. This is package-private so tests can verify it.

## Testing Strategy

### Unit tests (ContextCurationServiceTest)

| Test | Input | Expected |
|---|---|---|
| `testCountTokens_knownString()` | `"hello world"` | Exactly 2 tokens (cl100k_base) |
| `testCountTokens_javaCode()` | `"public class Test { }"` | Exactly 6 tokens |
| `testJtokkitAccuracy()` | A known 100-token reference string | `countTokens` returns 100 |
| `testJtokkitFallback()` | Simulate encoding failure | Falls back to heuristic |
| `testEmptyInput()` | `""`, `null` | Returns 0 |

### Integration tests

| Test | What It Verifies |
|---|---|
| `testCurateForQuery_returnsWithinBudget()` | Real token budget is respected (existing test, now accurate) |
| `testBudgetPostCheck_trimsWhenExceeded()` | Trim logic still works (existing test, now accurate) |
| `testDefaultTokenBudget()` | Default 2000 budget is applied (unchanged) |

### Manual verification

- Hit `POST /api/graph/curate` with known query + budget
- Verify `tokenCount` in response is accurate (cross-check against tiktoken Python CLI)
- Verify budget enforcement: `tokenCount <= budget` always holds

## Open Questions

1. **Encoding selection.** Using `cl100k_base` (GPT-4) as default is reasonable for most code-heavy prompts. If we later need multi-model support, we can parameterize the encoding name via configuration (`axolotl.tokenizer.encoding`).
2. **Entrypoint overhead.** jtokkit lazy-loads encoding tables (~2MB). First call is ~10ms to load the BPE ranks. Subsequent calls are ~0.01ms. For a curation endpoint called <1 req/sec, this is irrelevant.
3. **Ollama/local models.** jtokkit uses OpenAI tokenizer. For local models (Llama, Gemma), the tokenizer differs. However, the curation service is model-agnostic by design — using any BPE tokenizer is more accurate than the char heuristic. If specific local model tokenizer support is needed later, we can add a `EncodingType` configuration parameter.

## Effort Estimate

| Task | Time |
|---|---|
| Add jtokkit dependency to pom.xml | 2 min |
| Rewrite `curateForQuery` loop (remove maxChars, add jtokkit-based live counting) | 30 min |
| Replace `countTokens()` body | 5 min |
| Remove dead constants + tiebreaker | 5 min |
| Add MetricsService counters | 5 min |
| Update existing tests + add new tests | 15 min |
| Manual verification | 10 min |
| **Total** | **~1 hour** |

## Dependencies

- **New:** `com.knuddels:jtokkit:1.1.0` (Maven Central, Apache 2.0)
- **Runtime:** No new runtime requirements — pure Java, no native libraries
- **Scope:** Runtime-scoped dependency (also needed at compile time for the `Encoding` class)
