# Old Plans — Decent Seed Ideas

Extracted from rejected roadmap plans that contained useful kernels worth remembering.

## Seed 1: Cypher Aggregate Query Service (from plan 01 — NodePerformanceMetrics)

Plan 01's core concept — tracking per-node execution metrics (duration, token usage, error rates) — is mostly covered by existing `NodeExecution` Neo4j persistence. What's missing is a **generic Cypher aggregate query API**:

- `GET /api/stats/nodes?groupBy=type&aggregate=avg(durationMs),sum(tokensUsed)`
- Useful for the dashboard or an admin panel
- Could be implemented as a thin service class wrapping a few parameterized Cypher queries
- Not high priority, but cheap to build when the need arises

## Seed 2: Save Schema as Template (from plan 07 — Workflow Template System)

The current Quick Start presets are hardcoded. Users should be able to save their own schemas as reusable templates:

- "Save as Template" button in Studio toolbar
- Template includes: node types, config (minus model selection), edges, edge labels
- Saved to Neo4j as `WorkflowTemplate` nodes
- Available in Dashboard "Create from Template" dialog
- Timeline: trivial backend (new `TemplateService` + `TemplateController`), ~1 day frontend work

## Seed 3: Node Config A/B Testing (from plan 16 — A/B Testing Framework)

Ability to run the same node with different configurations (e.g. two different models, two different prompts) and compare outputs:

- Node gets a "variants" section: clone the node N times with different configs
- Execution runs all variants in parallel
- Results side-by-side in the timeline
- Heavy UI work; backend is straightforward (parallel `executeNode` with different configs)
- Worth revisiting when users need to compare model outputs systematically
