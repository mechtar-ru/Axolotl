# Axolotl Development Roadmap

**Status:** Approved — June 2026  
**Scope:** Near-term to medium-term (3-6 months)  
**Philosophy:** Build what's needed now, not what's hypothetically useful later.

---

## Phase 0 — Reliability (1-2 sprints, current)

*Make schema execution end-to-end reliable before adding new capabilities.*

### Must fix
- [ ] **Intermittent 403 on execute/pipeline endpoints** — root-cause the JWT + SecurityConfig interaction
- [ ] **JVM shutdown during executor calls** — confirm fixed or find remaining cause
- [ ] **Timeline Run History** — merge to main and validate it's useful for debugging failed executions
- [ ] **Бережно emotion diary** — run end-to-end successfully as acceptance test of the whole pipeline

### Must have working
- [ ] Studio → Quick Start → execute → results visible in Timeline (no silent failures)
- [ ] Review node approval flow (manual mode) works without errors
- [ ] Agent node generates files in targetPath that survive schema re-execution
- [ ] Verifier node produces PASS/FAIL with visible checks

### If time
- [ ] Pipeline TDD mode usability — easy to toggle, clear stage labels, test results visible
- [ ] Per-node manual re-execution from Studio

---

## Phase 1 — Provider Diversity (3-4 sprints)

*Axolotl should work with more than one LLM provider.*

### Why
- Single point of failure (Zen goes down → Axolotl is dead)
- Model ensemble routing requires 2+ working providers before it's even testable
- Users need local fallback for sensitive work

### Scope
- [ ] **Ollama integration** — test with existing provider abstraction; fix API compatibility issues
- [ ] **DeepSeek provider** — verify API key + endpoint work; add to test suite
- [ ] **OpenAI provider** — verify and document setup
- [ ] **Provider health dashboard** — Settings view shows live status per provider (green/red, last error, latency)
- [ ] **Graceful degradation** — if primary provider fails, cascade to fallback with user notification
- [ ] **Simple model routing** — user selects default model; schema can override per-node. No adaptive routing yet.

### Non-goals
- Model ensemble (requires data we don't have)
- Automatic failover (start with manual fallback)
- Cost optimization (one provider, one price)

---

## Phase 2 — Multi-Session Workflows (next quarter)

*Turn Axolotl into a reliable multi-session app generator.*

### Why
- Current apps are static — one schema execution produces files, then context is lost for the next session
- Agents can't reference prior execution results without parsing pipeline-reports
- The "generatedFiles" JSON list is the only cross-session bridge

### Scope
- [ ] **Session memory** — agents can query prior execution outputs from Neo4j directly (structured, not regex-hacked)
- [ ] **Plan-aware execution** — schema execution auto-creates plan tasks; completed tasks visible in Studio
- [ ] **Cross-session artifacts** — structured dependency tracking between files created in different sessions
- [ ] **Execution diff** — show what changed between session N and N+1
- [ ] **Rollback** — ability to revert files to a previous session's state

### Keep
- [ ] **Fixed pipeline template** — Quick Start stays as Receive → Review → Agent → Verify → Output
- [ ] **No LLM-driven node generation** — don't revisit

---

## Phase 3 — Post-Adoption (only after 10+ active users)

*Revisit optimization and analytics features from the old ROADMAP.*

### Gate condition
Axolotl has 10+ users who have each created 5+ schemas, totaling 100+ schema executions providing real usage data.

### Then consider
- Node performance analytics (which models/nodes are slow, expensive, or failing)
- Workflow pattern detection (recurring sub-graphs worth templating)
- Trajectory extraction for fine-tuning (only if open-weight models are in use)
- Model ensemble routing (only if 3+ providers are actively used)
- Anything from the archived `000-vision.md` or `roadmap_01-42.md` in `.ideas/misc/`

---

## Archive

Old roadmap files moved to `.ideas/misc/`:
- `001.md` — Big vision / aspirational product concept (archived)
- `roadmap_01.md` through `roadmap_42.md` — 12-month AI OS plan (archived)

Rejected approaches documented in `.ideas/rejected/REJECTED.md`.

The old roadmap wasn't wrong — it was written for a mature product. These phases match where Axolotl actually is right now.
