# Game Creation Support for Axolotl

**Goal:** Add `GAME` app type to Axolotl's app creation system, enabling users to create and run game apps (starting with Sokoban).

**Architecture:** Extend the existing app type system (enum + frontend union type) with a new `GAME` variant. Create a Sokoban template on both backend (via `AppController` starter templates) and frontend (via `templates/index.ts`). Add a `GameAppUI` runtime component for playing generated games. Wire it into `LiveView.vue` routing.

**Design:** `thoughts/shared/designs/2026-05-11-game-creation-design.md`

**Gap-filling decisions:**
- **Game icon:** Using a gamepad SVG path (`M...`) since design doesn't specify an icon. Following existing pattern (`CHAT` has a message icon, etc.)
- **Game color:** Using `#e91e63` (pink/rose) to distinguish from other types. Design doesn't specify colors.
- **GameAppUI behavior:** Following `GenericAppUI` pattern with input/output panel, but specialized for game interaction (level input, game state display).
- **App type label mapping:** Adding `'GAME': 'Game'` to the label map in `AppCard.vue`.
- **Sokoban template nodes:** A 3-node pipeline (source → agent → output) where the agent generates a playable HTML/JS game based on grid parameters. Following existing `template-content` pattern.

---

## Dependency Graph

```
Batch 1 (parallel): 1.1, 1.2, 1.3, 1.4 [foundation - no deps]
Batch 2 (parallel): 2.1, 2.2, 2.3 [templates & UI primitives - depend on Batch 1 types]
Batch 3 (parallel): 3.1, 3.2, 3.3, 3.4 [integration - depend on Batch 2]
```

---

## Batch 1: Foundation (parallel — 4 implementers)

All tasks in this batch have NO dependencies and run simultaneously.

### Task 1.1: Add GAME to Backend AppType Enum
**File:** `backend/src/main/java/com/agent/orchestrator/model/AppModel.java`
**Test:** `backend/src/test/java/com/agent/orchestrator/model/AppModelTest.java`
**Depends:** none

**Test (AppModelTest.java):**
```java
package com.agent.orchestrator.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppModelTest {

    @Test
    void testAppTypeEnumContainsGame() {
        assertNotNull(AppModel.AppType.valueOf("GAME"));
    }

    @Test
    void testGameAppTypeDefaultBehavior() {
        AppModel app = new AppModel("1", "Test Game", "A game", "ws1", AppModel.AppType.GAME);
        assertEquals(AppModel.AppType.GAME, app.getAppType());
        assertEquals("GAME", app.toSchema().getAppType());
    }

    @Test
    void testFromSchemaWithGameType() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("1");
        schema.setName("Sokoban");
        schema.setAppType("GAME");
        AppModel model = AppModel.fromSchema(schema);
        assertEquals(AppModel.AppType.GAME, model.getAppType());
    }

    @Test
    void testInvalidAppTypeFallsBackToCustom() {
        WorkflowSchema schema = new WorkflowSchema();
        schema.setId("1");
        schema.setName("Unknown");
        schema.setAppType("INVALID_TYPE");
        AppModel model = AppModel.fromSchema(schema);
        assertEquals(AppModel.AppType.CUSTOM, model.getAppType());
    }
}
```

**Implementation (edit AppModel.java):**
Edit the `AppType` enum at line 4 to add `GAME`:

```java
public enum AppType {
    CHAT,
    ANALYZER,
    GENERATOR,
    EMAIL,
    GAME,
    CUSTOM
}
```

**Verify:** `cd backend && mvn test -Dtest=AppModelTest`
**Commit:** `feat(backend): add GAME app type to AppType enum`

---

### Task 1.2: Update Frontend WorkflowSchema Type
**File:** `frontend/src/types/index.ts`
**Test:** none (single file change)
**Depends:** none

**Implementation (edit `frontend/src/types/index.ts`):**

Add `appType` field to the `WorkflowSchema` interface (after line 88, before the closing `}`):

```typescript
  appType?: string;
```

The interface after changes (showing the full interface for context):

```typescript
export interface WorkflowSchema {
  id: string;
  name: string;
  description: string;
  version: string;
  nodes: FlowNode[];
  edges: FlowEdge[];
  defaultModel?: string;
  metadata?: Record<string, any>;
  createdAt?: string;
  updatedAt?: string;
  appType?: string;
}
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(frontend): add appType field to WorkflowSchema interface`

---

### Task 1.3: Create GameAppUI Runtime Component
**File:** `frontend/src/components/live/GameAppUI.vue`
**Test:** `frontend/src/components/live/GameAppUI.test.ts`
**Depends:** none

**Test (GameAppUI.test.ts):**
```typescript
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GameAppUI from './GameAppUI.vue'

describe('GameAppUI', () => {
  it('renders the game interface', () => {
    const wrapper = mount(GameAppUI)
    expect(wrapper.exists()).toBe(true)
  })

  it('shows input section for game parameters', () => {
    const wrapper = mount(GameAppUI)
    expect(wrapper.text()).toContain('Level Configuration')
  })

  it('shows level input by default', () => {
    const wrapper = mount(GameAppUI)
    const textarea = wrapper.find('textarea')
    expect(textarea.exists()).toBe(true)
  })

  it('shows a run button to start the game', () => {
    const wrapper = mount(GameAppUI)
    const button = wrapper.find('button')
    expect(button.exists()).toBe(true)
    expect(button.text()).toContain('Start')
  })
})
```

**Implementation (new file `frontend/src/components/live/GameAppUI.vue`):**
```vue
<script setup lang="ts">
import { ref } from 'vue'

const levelConfig = ref('')
const gameOutput = ref<string | null>(null)
const isRunning = ref(false)

function startGame() {
  if (!levelConfig.value.trim() || isRunning.value) return

  isRunning.value = true
  gameOutput.value = null

  // Simulate game startup — will be replaced with WebSocket connection
  setTimeout(() => {
    gameOutput.value = `Game started with configuration:\n\n${levelConfig.value}\n\n---\n\n[Game output will appear here once execution begins. Connect WebSocket for real-time results.]`
    isRunning.value = false
  }, 800)
}
</script>

<template>
  <div class="game-app-ui">
    <div class="game-panel">
      <div class="game-section input-section">
        <h3 class="game-title">Level Configuration</h3>
        <textarea
          v-model="levelConfig"
          class="game-textarea"
          placeholder="Enter game parameters (e.g. grid size, level design)..."
          rows="6"
          :disabled="isRunning"
        />
        <button
          class="start-btn"
          @click="startGame"
          :disabled="!levelConfig.trim() || isRunning"
        >
          <svg v-if="isRunning" viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
            <rect x="6" y="4" width="4" height="16" rx="1"/>
            <rect x="14" y="4" width="4" height="16" rx="1"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="currentColor" width="16" height="16">
            <path d="M8 5v14l11-7z"/>
          </svg>
          {{ isRunning ? 'Starting...' : 'Start Game' }}
        </button>
      </div>

      <div class="game-divider" />

      <div class="game-section output-section">
        <h3 class="game-title">Game Output</h3>
        <div v-if="!gameOutput" class="output-placeholder">
          <div class="placeholder-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="40" height="40">
              <rect x="2" y="6" width="20" height="12" rx="2" />
              <path d="M6 12h4" />
              <path d="M14 12h4" />
              <path d="M6 16h4" />
              <path d="M14 16h4" />
            </svg>
          </div>
          <p>Configure your level and click Start Game to play</p>
        </div>
        <pre v-else class="output-content">{{ gameOutput }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.game-app-ui {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 2rem;
}

.game-panel {
  display: flex;
  width: 100%;
  max-width: 900px;
  height: 100%;
  gap: 1.5rem;
}

.game-section {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.game-title {
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 0.75rem 0;
}

.game-textarea {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  background: var(--bg-secondary);
  color: var(--text-primary);
  resize: none;
  font-family: monospace;
  line-height: 1.6;
}

.game-textarea:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-bg);
}

.game-textarea:disabled {
  opacity: 0.6;
}

.start-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  margin-top: 0.75rem;
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: white;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  align-self: flex-start;
  transition: background 0.15s;
}

.start-btn:hover:not(:disabled) {
  background: var(--accent-light);
}

.start-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.game-divider {
  width: 1px;
  background: var(--border-color);
  flex-shrink: 0;
}

.output-placeholder {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--border-color);
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 0.85rem;
  gap: 0.75rem;
}

.placeholder-icon {
  opacity: 0.4;
}

.output-content {
  flex: 1;
  padding: 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  font-size: 0.85rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-y: auto;
  color: var(--text-primary);
  margin: 0;
  font-family: monospace;
}
</style>
```

**Verify:** `cd frontend && npm run test:unit -- --run src/components/live/GameAppUI.test.ts`
**Commit:** `feat(frontend): add GameAppUI runtime component`

---

### Task 1.4: Add GAME to TemplateCard Colors and AppCard Display Maps
**File:** `frontend/src/components/app/TemplateCard.vue` + `frontend/src/components/app/AppCard.vue`
**Test:** none (visual consistency)
**Depends:** none

**Implementation for TemplateCard.vue (add to `bgColors` and `accentColors` maps):**

Edit the `bgColors` object (line 16-22) — add `GAME` entry:
```typescript
const bgColors: Record<string, string> = {
  CHAT: 'rgba(76, 175, 80, 0.1)',
  ANALYZER: 'rgba(33, 150, 243, 0.1)',
  GENERATOR: 'rgba(255, 152, 0, 0.1)',
  EMAIL: 'rgba(156, 39, 176, 0.1)',
  GAME: 'rgba(233, 30, 99, 0.1)',
  CUSTOM: 'rgba(108, 99, 255, 0.1)'
}
```

Edit the `accentColors` object (line 24-30) — add `GAME` entry:
```typescript
const accentColors: Record<string, string> = {
  CHAT: '#4caf50',
  ANALYZER: '#2196f3',
  GENERATOR: '#ff9800',
  EMAIL: '#9c27b0',
  GAME: '#e91e63',
  CUSTOM: '#6c63ff'
}
```

**Implementation for AppCard.vue (add to `appTypeIcons`, `appTypeColors`, and label):**

Edit `appTypeIcons` object (line 28-34) — add `GAME` entry:
```typescript
const appTypeIcons: Record<string, string> = {
  CHAT: 'M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z',
  ANALYZER: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
  GENERATOR: 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z',
  EMAIL: 'M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
  GAME: 'M6 12h4m4 0h4M6 16h4m4 0h4M6 8h12M4 6a2 2 0 012-2h12a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V6z',
  CUSTOM: 'M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4'
}
```

Edit `appTypeColors` object (line 36-42) — add `GAME` entry:
```typescript
const appTypeColors: Record<string, string> = {
  CHAT: '#4caf50',
  ANALYZER: '#2196f3',
  GENERATOR: '#ff9800',
  EMAIL: '#9c27b0',
  GAME: '#e91e63',
  CUSTOM: '#6c63ff'
}
```

Also update `getAppTypeLabel` function (line 48-50) to return a nice label:
```typescript
function getAppTypeLabel(type?: string): string {
  const labels: Record<string, string> = {
    CHAT: 'Chat',
    ANALYZER: 'Analyzer',
    GENERATOR: 'Generator',
    EMAIL: 'Email',
    GAME: 'Game',
    CUSTOM: 'Custom'
  }
  return labels[type || 'CUSTOM'] || type || 'CUSTOM'
}
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(frontend): add GAME branding to AppCard and TemplateCard maps`

---

## Batch 2: Templates & UI Integration (parallel — 3 implementers)

### Task 2.1: Add GAME Type to Templates Registry
**File:** `frontend/src/templates/index.ts`
**Test:** `frontend/src/templates/index.test.ts`
**Depends:** 1.2 (types/index.ts union type)

**Test (index.test.ts):**
```typescript
import { describe, it, expect } from 'vitest'
import { templates, getTemplateById, getTemplatesByType } from './index'

describe('templates index', () => {
  it('includes the Sokoban game template', () => {
    const sokoban = getTemplateById('template-sokoban')
    expect(sokoban).toBeDefined()
    expect(sokoban?.name).toBe('Sokoban Game')
  })

  it('has GAME appType on Sokoban template', () => {
    const sokoban = getTemplateById('template-sokoban')
    expect(sokoban?.appType).toBe('GAME')
  })

  it('filters templates by GAME type', () => {
    const gameTemplates = getTemplatesByType('GAME')
    expect(gameTemplates.length).toBeGreaterThanOrEqual(1)
    expect(gameTemplates[0]!.id).toBe('template-sokoban')
  })

  it('does not break existing CHAT templates', () => {
    const chat = getTemplateById('template-chat')
    expect(chat).toBeDefined()
    expect(chat?.appType).toBe('CHAT')
  })
})
```

**Implementation (edit `frontend/src/templates/index.ts`):**

First, update the `AppTemplate` interface (line 5) to include `'GAME'`:
```typescript
export interface AppTemplate {
  id: string
  name: string
  description: string
  appType: 'CHAT' | 'ANALYZER' | 'GENERATOR' | 'EMAIL' | 'GAME' | 'CUSTOM'
  defaultNodes: Array<{
    id: string
    type: string
    name: string
    position: { x: number; y: number }
    data?: Record<string, unknown>
  }>
  defaultEdges: Array<{
    id: string
    source: string
    target: string
  }>
}
```

Then add the Sokoban template to the `templates` array. Add it between the Data Extractor and Blank App entries (after line 214):

```typescript
  {
    id: 'template-sokoban',
    name: 'Sokoban Game',
    description: 'Generate a playable Sokoban puzzle game from grid parameters',
    appType: 'GAME',
    defaultNodes: [
      {
        id: 'receive-1',
        type: 'source',
        name: 'Game Parameters',
        position: { x: 100, y: 200 },
        data: { sourceData: 'Grid size, level layout, and game rules' }
      },
      {
        id: 'think-1',
        type: 'agent',
        name: 'Generate Game',
        position: { x: 450, y: 200 },
        data: {
          systemPrompt: 'You are a game developer. Generate a complete playable Sokoban game as HTML with embedded CSS and JavaScript. The game must include: a grid-based level, player character, walls, boxes, target spaces, movement controls (arrow keys), undo functionality, level reset, move counter, and victory detection. Output ONLY the complete HTML file.',
          userPrompt: 'Create a Sokoban game with these parameters:\n\nGrid: {{grid}}\nLevel: {{level}}\n\nGenerate a self-contained HTML file.'
        }
      },
      {
        id: 'act-1',
        type: 'output',
        name: 'Playable Game',
        position: { x: 800, y: 200 },
        data: {}
      }
    ],
    defaultEdges: [
      { id: 'e1', source: 'receive-1', target: 'think-1' },
      { id: 'e2', source: 'think-1', target: 'act-1' }
    ]
  },
```

**Verify:** `cd frontend && npm run test:unit -- --run src/templates/index.test.ts`
**Commit:** `feat(frontend): add Sokoban game template to templates registry`

---

### Task 2.2: Add Game Templates to Backend AppController Starter Templates
**File:** `backend/src/main/java/com/agent/orchestrator/controller/AppController.java`
**Test:** none (existing controller test pattern)
**Depends:** 1.1 (AppModel.AppType enum)

**Implementation (edit `AppController.java`):**

Add a GAME template to the `getTemplates()` method. Insert it after the Email Agent template (after line 130, before the Data Extractor):

```java
// Template 5: Sokoban Game
Map<String, Object> sokoban = new LinkedHashMap<>();
sokoban.put("id", "template-sokoban");
sokoban.put("name", "Sokoban Game");
sokoban.put("description", "Generate a playable Sokoban puzzle game");
sokoban.put("appType", "GAME");
sokoban.put("nodes", List.of());
sokoban.put("edges", List.of());
templates.add(sokoban);
```

Note: The existing templates will be renumbered. The final order should be:
1. Chat Bot (CHAT)
2. Document Analyzer (ANALYZER)
3. Content Generator (GENERATOR)
4. Email Agent (EMAIL)
5. Sokoban Game (GAME)  ← NEW
6. Data Extractor (ANALYZER)
7. Blank App (CUSTOM)

**Verify:** `cd backend && mvn compile`
**Commit:** `feat(backend): add Sokoban game template to AppController`

---

### Task 2.3: Add Game Template to Backend TemplateController (Sokoban workflow)
**File:** `backend/src/main/java/com/agent/orchestrator/controller/TemplateController.java`
**Test:** none (existing pattern)
**Depends:** 1.1 (AppModel.AppType enum, used for conceptual alignment)

**Implementation (edit `TemplateController.java`):**

Add a `sokobanGameTemplate()` method and register it in `getTemplates()` and `getTemplate()`:

Update the `getTemplates()` method (line 13-15) to include both templates:
```java
@GetMapping
public List<Map<String, Object>> getTemplates() {
    return List.of(projectPlanningTemplate(), sokobanGameTemplate());
}
```

Update `getTemplate()` (line 17-21) to handle the new ID:
```java
@GetMapping("/{id}")
public Map<String, Object> getTemplate(@PathVariable String id) {
    if ("project-planning".equals(id)) return projectPlanningTemplate();
    if ("sokoban-game".equals(id)) return sokobanGameTemplate();
    throw new NoSuchElementException("Template not found: " + id);
}
```

Add the new method before the closing brace of the class:
```java
private Map<String, Object> sokobanGameTemplate() {
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("id", "sokoban-game");
    t.put("name", "Sokoban Game Generator");
    t.put("description", "Generate a playable Sokoban puzzle game from grid parameters and level design");
    t.put("icon", "🎮");

    // Nodes
    List<Map<String, Object>> nodes = new ArrayList<>();

    // Source: game parameters
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("id", "s1");
    source.put("type", "source");
    source.put("name", "Game Parameters");
    source.put("position", Map.of("x", 100, "y", 50));
    Map<String, Object> sourceData = new LinkedHashMap<>();
    sourceData.put("config", Map.of(
            "sourceType", "text",
            "grid", "8x8",
            "level", "Classic Sokoban level 1"
    ));
    source.put("data", sourceData);
    nodes.add(source);

    // Agent: Game Generator
    Map<String, Object> gameAgent = new LinkedHashMap<>();
    gameAgent.put("id", "a1");
    gameAgent.put("type", "agent");
    gameAgent.put("name", "Game Generator");
    gameAgent.put("position", Map.of("x", 100, "y", 250));
    Map<String, Object> agentData = new LinkedHashMap<>();
    agentData.put("systemPrompt",
        "You are a game developer specializing in generating playable HTML/JS games. " +
        "Given grid parameters and level designs, you must output a COMPLETE, self-contained HTML file " +
        "that includes all CSS and JavaScript inline. The game must be immediately playable in a browser. " +
        "Include: grid rendering, player movement (arrow keys), collision detection, win condition, " +
        "move counter, undo functionality, and visual feedback.");
    agentData.put("userPrompt",
        "Generate a playable Sokoban game with these specifications:\n" +
        "- Grid dimensions: {{grid}}\n" +
        "- Level design: {{level}}\n\n" +
        "Output only the complete HTML file content.");
    agentData.put("model", "");
    gameAgent.put("data", agentData);
    nodes.add(gameAgent);

    // Output: generated game
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("id", "o1");
    output.put("type", "output");
    output.put("name", "Generated Game");
    output.put("position", Map.of("x", 100, "y", 450));
    output.put("data", Map.of("outputType", "log"));
    nodes.add(output);

    t.put("nodes", nodes);

    // Edges
    List<Map<String, String>> edges = new ArrayList<>();
    edges.add(Map.of("id", "e1", "source", "s1", "target", "a1"));
    edges.add(Map.of("id", "e2", "source", "a1", "target", "o1"));
    t.put("edges", edges);

    // Variables users should fill in
    t.put("variables", List.of(
        Map.of("name", "grid", "description", "Grid dimensions (e.g. 8x8)", "required", true, "nodeId", "s1", "field", "config.grid"),
        Map.of("name", "level", "description", "Level layout description or JSON", "required", true, "nodeId", "a1", "field", "userPrompt")
    ));

    return t;
}
```

**Verify:** `cd backend && mvn compile`
**Commit:** `feat(backend): add Sokoban game template to TemplateController`

---

## Batch 3: Integration (parallel — 2 implementers)

### Task 3.1: Update DashboardView with GAME Template and Select Option
**File:** `frontend/src/views/DashboardView.vue`
**Test:** none (visual component change)
**Depends:** 2.1, 2.2 (needs GAME templates and cards)

**Implementation (edit `DashboardView.vue`):**

1. Add a Sokoban template entry to the `templates` ref array. Insert it before the Blank App entry (after the Data Extractor template, around line 46):

```typescript
  {
    id: 'template-sokoban',
    name: 'Sokoban Game',
    description: 'Generate a playable Sokoban puzzle game',
    appType: 'GAME',
    icon: 'M6 12h4m4 0h4M6 16h4m4 0h4M6 8h12M4 6a2 2 0 012-2h12a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V6z'
  },
```

2. Add `GAME` option to the New App modal select (in the `<select>` at line 156-162):

```html
          <select v-model="newAppType" class="input">
            <option value="CUSTOM">Custom</option>
            <option value="CHAT">Chat Bot</option>
            <option value="ANALYZER">Analyzer</option>
            <option value="GENERATOR">Generator</option>
            <option value="EMAIL">Email Agent</option>
            <option value="GAME">Game</option>
          </select>
```

3. Ensure `createFromTemplate` passes `appType` when creating the schema (line 73-80). The current implementation only passes the name but not the appType. Update the call:

```typescript
async function createFromTemplate(templateId: string) {
  const template = templates.value.find(t => t.id === templateId)
  if (!template) return

  try {
    const schema = await schemaStore.createSchema(template.name, template.appType)
    if (schema) {
      router.push(`/app/${schema.id}`)
    }
  } catch (error) {
    console.error('Failed to create schema from template:', error)
  }
}
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(frontend): add Sokoban template and GAME option to DashboardView`

---

### Task 3.2: Wire GameAppUI into LiveView Routing
**File:** `frontend/src/components/studio/LiveView.vue`
**Test:** none (visual routing component)
**Depends:** 1.3 (GameAppUI.vue), 1.2 (types)

**Implementation (edit `LiveView.vue`):**

1. Add import for GameAppUI (after line 6):

```typescript
import GameAppUI from '@/components/live/GameAppUI.vue'
```

2. Add routing condition in the template (after line 30):

```vue
      <GameAppUI v-else-if="appType === 'GAME'" />
```

The full conditional template block becomes:

```vue
      <ChatAppUI v-if="appType === 'CHAT'" />
      <DocAnalyzerAppUI v-else-if="appType === 'ANALYZER'" />
      <GameAppUI v-else-if="appType === 'GAME'" />
      <GenericAppUI v-else :app-type="appType" />
```

**Verify:** `cd frontend && npm run type-check`
**Commit:** `feat(frontend): route GAME app type to GameAppUI in LiveView`

---

## Summary of All Changes

| # | File | Change | Batch | Deps |
|---|------|--------|-------|------|
| 1.1 | `backend/.../AppModel.java` | Add `GAME` to `AppType` enum | 1 | none |
| 1.2 | `frontend/src/types/index.ts` | Add `appType` field to `WorkflowSchema` | 1 | none |
| 1.3 | `frontend/src/components/live/GameAppUI.vue` | New game runtime component | 1 | none |
| 1.4 | `frontend/src/components/app/TemplateCard.vue` + `AppCard.vue` | Add GAME colors/icons/labels | 1 | none |
| 2.1 | `frontend/src/templates/index.ts` | Add GAME to union type + Sokoban template | 2 | 1.2 |
| 2.2 | `backend/.../AppController.java` | Add Sokoban starter template | 2 | 1.1 |
| 2.3 | `backend/.../TemplateController.java` | Add Sokoban workflow template | 2 | 1.1 |
| 3.1 | `frontend/src/views/DashboardView.vue` | Add Sokoban template card + GAME select option | 3 | 2.1, 2.2 |
| 3.2 | `frontend/src/components/studio/LiveView.vue` | Route GAME → GameAppUI | 3 | 1.3, 1.2 |
