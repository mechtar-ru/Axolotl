---
date: 2026-05-13
topic: Dashboard Redesign - Unified Apps + Compact Paths
status: draft
---

## Problem Statement

The dashboard has three structural issues that hurt UX:

1. **Two app sections** — "My Generated Apps" and "My Apps" show the same kind of objects (schemas) in different card formats, which confuses users
2. **Expressive paths** — Full absolute paths like `/Users/evgenijtihomirov/git/Axolotl/Sokoban Game/` dominate generated app cards — ~50 chars of visual noise that makes the card about the path, not the app name
3. **Inconsistent card sizes** — Generated apps get full-width inline cards with two buttons, while regular apps get compact grid cards. They should be consistent
4. **Template section has top billing** — Templates are one-time-use and shouldn't sit above owned content

## Constraints

- No new packages or dependencies
- Path display must retain ability to copy/share the full path (tooltip + optional copy button)
- Generated app actions ("Open target directory", "Continue Dev" → execute the schema) must be preserved
- Existing `AppCard.vue` component should be reused, not replaced
- Must work with current Pinia stores and API services — no backend changes

## Approach Chosen

**Unify all apps into a single grid using AppCard, extend AppCard to carry generated-app metadata, demote templates.**

Rationale: This eliminates the conceptual split between "generated" and "non-generated" apps. The user just has apps — some happen to write files to disk. The card component already has the right visual structure; we add one secondary line for the path.

## Architecture

### Current vs Proposed Layout

```
CURRENT                              PROPOSED
┌─────────────────────┐              ┌─────────────────────┐
│ 🎯 Welcome...                      │ 🔍 [Search...]      │  ← new
│ [New App]                          │ [New App]           │
│                                     │                     │
│ 📦 Generated Apps                  │ My Apps             │
│ ┌─ full width card ──────────┐     │ ┌───┐ ┌───┐ ┌───┐  │
│ │ /Users/...long path.../    │     │ │ G │ │ A │ │ B │  │  ← unified grid
│ │ [Open] [Continue]          │     │ └───┘ └───┘ └───┘  │
│ └────────────────────────────┘     │                     │
│                                     │ Start from Template │
│ My Apps (AppCard grid)             │ ┌───┐ ┌───┐ ┌───┐  │  ← moved down
│ ┌───┐ ┌───┐ ┌───┐                  │ │ T │ │ T │ │ T │  │
│ │ A │ │ B │ │ C │                  │ └───┘ └───┘ └───┘  │
│ └───┘ └───┘ └───┘                  └─────────────────────┘
│
│ Start from Template
│ ┌───┐ ┌───┐ ┌───┐
│ │ T │ │ T │ │ T │
│ └───┘ └───┘ └───┘
└─────────────────────┘
```

### Component Changes

| Component | Change |
|-----------|--------|
| `DashboardView.vue` | Remove separate "Generated Apps" section, add search input, add path column to generated items in the unified list, demote templates section |
| `AppCard.vue` | Accept optional `targetPath` prop, optional `isGenerated` flag, optional `status` prop — show path line + status badge below description |
| `TemplateCard.vue` | No changes (will still be rendered, just lower on the page) |

### Data Flow

1. `DashboardView` computes `generatedApps` (existing dedup logic by `targetPath`)
2. Merges `generatedApps` into `schemaStore.schemas` for the unified grid — keyed by schema `id`
3. Passes `targetPath` and status as props to each `AppCard`
4. `AppCard` conditionally shows the path line when `targetPath` is present
5. Search filter is a simple `computed` that filters the merged list by name match (case-insensitive)

## Components

### DashboardView.vue Changes

```
Remove:
  - Entire "My Generated Apps" section (lines ~162-240)
  - Section header "Generated Apps"
  - Inline .generated-app-card rendering

Add:
  - Search input between header and "My Apps" grid
  - hasGeneratedApps computed (same logic, used for section title only)
  - visibleApps computed that merges schemas + generatedApp props
  - section title: "My Apps" (always visible, no "Generated" split)

Modify:
  - v-for over schemas → v-for over visibleApps
  - Pass targetPath, isGenerated, status to AppCard
  - Move templates section below "My Apps"
```

### AppCard.vue Changes

```
Add props:
  - targetPath?: string       — full path (for tooltip + display)
  - isGenerated?: boolean     — shows folder icon + status dot
  - status?: 'active' | 'idle'  — green dot vs gray dot

Add to template (below description, when targetPath exists):
  - Row: folder SVG icon + truncated path (last 2 segments)
  - Show full path as title attribute (tooltip)
  - Status dot (green for active/schemas with sessions)

Path formatting:
  "~/Axolotl/Sokoban Game/"   — replace /Users/evgenijtihomirov with ~
                               — show last 2 segments only
```

### Search Filter

```
Simple text input above the grid:
  - v-model="searchQuery"
  - Placeholder: "Search apps..."
  - Computed: visibleApps filtered by name includes searchQuery
  - No debounce needed (small dataset, reactive filter)
```

## Data Flow

```
schemaStore.schemas ─┐
                     ├── mergeApps() ──→ visibleApps ──→ v-for AppCard
generatedApps ───────┘       ↑
                          filter (searchQuery)
```

The merge function:
```
function mergeApps(schemas, generatedApps): AppCardData[] {
  // Use generated entries by schema id, they have targetPath
  const genMap = new Map(generatedApps.map(g => [g.id, g]))
  return schemas.map(s => ({
    ...s,
    targetPath: genMap.get(s.id)?.targetPath,
    isGenerated: genMap.has(s.id),
    status: genMap.has(s.id) ? 'active' : 'idle'
  }))
}
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Search query matches nothing | Show "No apps matching "query"" with clear CTA |
| generatedApps fetch fails | Logged silently, schemas grid renders without path data |
| targetPath is null/undefined | Path line not rendered, no error |

## Testing Strategy

- **Visual**: Verify AppCard renders path line only when `targetPath` is passed
- **Filter**: Type in search, verify list narrows
- **Edge case**: Zero schemas → empty state still shows correctly
- **Edge case**: Mix of generated + regular → grid is uniform, paths shown only on generated cards
- **Responsive**: Grid still wraps at breakpoints (should be identical layout behavior)

## Open Questions

(none — all decisions made)
