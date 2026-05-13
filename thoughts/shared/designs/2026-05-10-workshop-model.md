# Workshop / Lab Bench — Axolotl Conceptual Model

**Date:** 2026-05-10
**Status:** Approved
**Decision driver:** @commander + user

## Core Metaphor

**Axolotl is a workshop workbench for building, testing, and iterating AI agent workflows.**

Not a flowchart editor. Not a pipeline configurator. A physical workbench where you:

- Lay out **components** on the bench
- **Wire** them together
- **Run tests** to see if they work
- Attach **probes** to inspect data flow
- **Measure** performance (latency, tokens, cost)
- Save **blueprints** and share **recipes**

This model was chosen over: Orchestra Conductor, Assembly Line, Creative Studio, Neural Network, Craftsman's Workbench.

**Why Workshop fits best:**
- Audience = builders who experiment and iterate
- Accommodates canvas (bench), execution (testing), memory (reference shelf), plans (lab notebook)
- Distinct from competitors: n8n (pipeline), LangFlow (flowchart), Zapier (automation)
- Extensible — a workshop naturally has specialized tools, safety gear (guardrails), spare parts (components)

## Terminology Map

| Current Term | New Term | Rationale |
|-------------|----------|-----------|
| Canvas | Workbench | You build on a bench, not draw on a canvas |
| Node | Component | Discrete part with a function, plugs into others |
| Edge | Wiring / Connection | Wires carry signals between components |
| Execute | Run Test | You test a build, not "execute" |
| Schema | Blueprint | Saved work is a reproducible plan |
| Node Palette | Toolbox | Where you pick tools/components from |
| Template | Recipe | A starting point you can follow and adapt |
| Plan | Lab Notebook | Observations, notes, experiment tracking |

## Greenlit Features

Features that emerge naturally from the Workshop model:

| Feature | Description | Priority |
|---------|-------------|----------|
| Test Bench | Run workflow with mock input before real execution | High |
| Probe Points | Inspect data flowing through any connection (visual debugging) | High |
| Measurements Panel | Per-component latency, tokens, cost (like a multimeter) | Medium |
| Component Catalog | Save/search custom components for reuse | Medium |
| Workbench Snapshots | Version history of bench setup, compare over time | Medium |
| Recipe Sharing | Publish/import blueprints as shareable recipes | Low |
| Scrap Bin | Recover deleted items instead of permanent delete | Low |

## Implementation Scope (Phase 1: Terminology)

### UI Text Changes

All user-facing text should use the new terminology:

- Button labels (e.g., "Execute" → "Run Test")
- Tooltips and help text
- Placeholder text
- Toast notifications
- Onboarding copy
- Empty-state messages
- Sidebar labels

### Code Artifacts (not changing — too high risk for rename)

Internal variable names, component IDs, and API routes stay as-is unless they leak into user-facing text. This avoids breaking serialization, stored schemas, and API contracts.

### File Coverage

- `frontend/src/views/HomeView.vue` — sidebar, onboarding, templates
- `frontend/src/components/ui/OnboardingModal.vue` — step descriptions
- `frontend/src/components/canvas/WorkflowCanvas.vue` — toolbar, context menus
- `frontend/src/components/execution/*.vue` — run/stop buttons, logs panel
- `frontend/src/components/nodes/*.vue` — node labels, port labels
- `frontend/src/components/ui/TemplateGallery.vue` — template labels
- `frontend/src/stores/*.ts` — only user-facing template strings

### Non-Goals for Phase 1
- No CSS/class renames
- No backend text changes (backend uses English IDs, not user-facing text)
- No Vue component renames
- No feature implementation (Test Bench, probes, etc. are separate phases)

## Implementation Plan

1. **Find all user-facing strings** matching old terms (canvas, node, edge, execute, schema, palette, template, plan)
2. **Replace in UI text** — keep internal code/references unchanged
3. **Verify no broken UI** — text-only changes, no functional impact
4. **Update AGENTS.md** onboarding description if needed

---

*This document is the source of truth for the Workshop model decision. All UI work should reference this for terminology.*
