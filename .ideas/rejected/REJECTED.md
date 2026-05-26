# Rejected Approaches

Ideas evaluated and explicitly declined. Kept for audit trail — don't revisit without new context.

| Idea | Why Rejected | Context |
|------|-------------|---------|
| SQLite as primary store | Full migration to Neo4j complete; dual-dB adds complexity with no benefit | Migrated 2026-04 |
| LLM-driven dynamic node generation (generate-nodes) | Quick Start uses fixed pipeline template; LLM-generated node graphs produced inconsistent, non-reproducible schemas | Deprecated April 2026 |
| Spring AI integration | Over-engineered for our use case; Zen provider uses direct HttpClient for better control and simpler debugging | Removed May 2026 |
| File-based execution persistence | Neo4j provides durability across page reloads, queryability, and consistency without file sync concerns | Replaced April 2026 |
| Unlimited thread pool for execution | SchemaService uses virtual threads (Java 21); fixed limits prevent resource exhaustion | Virtual threads adopted April 2026 |
| Emoji icons in frontend | Visual inconsistency across platforms; inline SVGs render reliably everywhere | User directive, eliminated May 2026 |
| Fine-tuning pipeline (ROADMAP Phase 2) | Only Zen API available — proprietary models can't be fine-tuned. Requires open-weight model provider (Ollama) and GPU infrastructure | Deferred indefinitely June 2026 |
| Model ensemble routing | Requires 3+ functioning providers; currently only Zen has an API key configured | Premature — revisit after 2nd provider |
| Self-optimizing workflow engine | Assumes large-scale user-generated workflows and extensive execution history; we have neither | Premature — revisit after 10+ real users |
| Collaboration / multi-user features | Single-developer tool at current stage; building team features solves problems we don't have | Premature — revisit when needed |
