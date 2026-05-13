---
date: 2026-05-11
topic: "Axolotl Studio — Redesign from workflow orchestrator to app builder"
status: validated
---

## Problem Statement

Axolotl currently presents itself as a "visual AI-agent orchestrator" — a technical tool for building DAGs of AI nodes. Users don't want to orchestrate agents. They want to **build AI-powered applications** visually. The current UX speaks the language of infrastructure (schemas, nodes, edges, execution modes, waves), while the user thinks in terms of products (chatbots, analyzers, generators).

The gap between "what the user wants to do" and "what the interface lets them do" is fatal: cognitive overload from 16 node types, 5 panel tabs, 7 modals, and technical jargon at every step.

## Approach

Shift the product's mental model from **"workflow orchestrator"** to **"AI application studio"** .

Three modes replace the current flat canvas + panel + modal sprawl:

1. **Blueprint** — design the architecture of your app with 4 block types (Receive, Think, Remember, Act)
2. **Live** — interact with your running app (chat UI, file upload, etc.)
3. **Timeline** — see what happened in human-readable form

Every decision in the design flows from one question: "Does this help the user build and run their app?"

## Constraints

- **VueFlow stays** as the canvas engine — no replacing the core rendering
- **Backend execution engine stays** — DAG topological sort, parallel CompletableFuture, WebSocket streaming
- **Neo4j/SQLite storage stays** — no database migration needed
- **Auth system stays** — JWT, login/register
- **No feature removal** that breaks existing saved schemas (migration path needed)
- **Light theme must be added** — dark-only is a barrier

## Architecture: Three Modes

### Mode: Blueprint (canvas)

The canvas is the primary surface. The layout strips away everything that isn't about designing your app's architecture.

**Layout:**
```
┌──────────────────────────────────────────────────┐
│ [App Name]     [Blueprint|Live|Timeline]  [▶ Run] │
├──────────────────────────────────────────────────┤
│                                                  │
│                   CANVAS                         │
│    [Receive] → [Think] → [Act]                  │
│                    ↓                             │
│               [Remember]                         │
│                                                  │
├──────────────────────────────────────────────────┤
│ [➕ Add Block]                          [⚙️]     │
└──────────────────────────────────────────────────┘
```

**Toolbar:**
- App name (inline editable)
- Mode tabs (Blueprint / Live / Timeline)
- Primary action button: "▶ Run" (large, green) / "■ Stop" (red, when running)
- User avatar → dropdown (settings, logout)

**No:**
- Sidebar with schema list
- Right panel tabs (exec/plan/memory/history/templates)
- Execution mode selector (EXECUTE/ANALYZE/DRY_RUN)
- Save/Export/Delete buttons in toolbar (auto-save, export in overflow menu)
- Search/SchemaBuilder/CommandPalette as main UI elements

### Mode: Live (app runtime)

When the user presses Run, the app transitions from Blueprint to Live. The Live view renders an **interactive interface** appropriate to the app's input/output type:

- **Chat apps** → chat window with message history, input field, streaming responses
- **Document processors** → file upload dropzone, progress indicator, results view
- **Content generators** → form inputs, output preview, copy/download actions
- **General** → an interactive console showing inputs/outputs per block

The Live view auto-determines the interface type from the graph structure (first block's type determines the UI). The user can also switch back to Blueprint at any time while the app runs.

### Mode: Timeline (trace)

A human-readable log of the app's execution. Not technical logs — a story:

```
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
  14:23:05  📩 Получено сообщение: "Как вернуть товар?"
            ↓
  14:23:05  🧠 Think: Анализ запроса (0.3s)
            → Определён тип: "возврат товара"
            ↓
  14:23:06  📖 Remember: Поиск политики возврата
            → Найдено 3 документа
            ↓
  14:23:07  🧠 Think: Генерация ответа (0.8s)
            → Сформирован ответ с запросом номера заказа
            ↓
  14:23:07  📤 Act: Отправка ответа пользователю
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
```

Each step maps to a block on the canvas and is clickable — clicking navigates back to Blueprint and selects that block.

### Navigation between modes

Three tabs at the top: Blueprint | Live | Timeline

- **Blueprint → Run** auto-switches to Live
- **Live → Stop** stays on Live (frozen state, user can review the interaction)
- **Timeline** is always accessible — shows last execution trace
- User can freely switch between them while an app is running

## Components

### Block types (replacing 16 node types)

| Block | Color | Shape | Function | Configuration |
|-------|-------|-------|----------|---------------|
| **Receive** | Green `#4caf50` | Rounded rect | Entry point for data | Type: chat, file, webhook, schedule. Description in NL |
| **Think** | Blue `#2196f3` | Hexagon | AI processing | "What should this do?" (NL). Model selector. Tools |
| **Remember** | Purple `#9c27b0` | Cylinder | Storage/retrieval | Memory type: conversation, knowledge, facts |
| **Act** | Orange `#ff9800` | Rect with notch | Output/action | Type: reply, save, call API, send |

No Edges types — all connections are simple data-flow arrows.

### Block configuration panel

Not a tabbed panel. A slide-over that appears when clicking a block:

```
┌─────────────────────┐
│ 🧠 Think            │
│                     │
│ "Что этот блок      │
│ должен делать?"     │
│ ┌─────────────────┐│
│ │ Анализируй       ││
│ │ сообщение и      ││
│ │ определи         ││
│ │ намерение        ││
│ └─────────────────┘│
│                     │
│ Модель: [GPT-4 ▼]   │
│                     │
│ Инструменты:        │
│ ☑ Web Search       │
│ ☑ File Read        │
│ ☐ File Write       │
│ ─────────────────   │
│ [+ Добавить условие]│
└─────────────────────┘
```

No separate tabs. No "System Prompt" / "User Prompt" fields. Single NL description that gets compiled into prompts.

### Dashboard (new first screen)

Replaces the current sidebar + empty canvas on first load:

```
┌──────────────────────────────────────────────────┐
│ 🧬 Axolotl Studio                    [👤 user ▼] │
├──────────────────────────────────────────────────┤
│                                                  │
│  👋 Welcome back, Alex                           │
│                                                  │
│  + New App                                       │
│                                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐ │
│  │ 💬 Support │  │ 📄 Doc     │  │ ✉️ Email   │ │
│  │   Chatbot  │  │ Analyzer   │  │ Assistant  │ │
│  │   ● Live   │  │ ○ Draft    │  │ ● Ready    │ │
│  │   2h ago   │  │            │  │            │ │
│  └────────────┘  └────────────┘  └────────────┘ │
│                                                  │
│  Start from a template:                          │
│  [Chat Bot] [Doc Analyzer] [Content Gen]         │
│  [Email Agent] [Data Extractor] [Custom ▸]      │
└──────────────────────────────────────────────────┘
```

### Light theme

A proper light color scheme:
- Background: `#f8f9fa` (cool off-white)
- Canvas: `#ffffff`
- Text: `#1a1a2e`
- Accent: `#6c63ff` (kept from dark theme)
- Blocks retain their colors but with lighter shades

Dark theme accessible via toggle in settings. Default determined by system preference.

## Data Flow

### User flow (happy path)

```
Login → Dashboard
  → Click "New App" or template
  → Blueprint view with blank or template canvas
  → Drag and configure blocks
  → Press "▶ Run"
  → Auto-switch to Live view
  → Interact with the running app
  → Press "■ Stop"
  → Review interactions in Live
  → Switch to Timeline for execution trace
  → Click a Timeline step → back to Blueprint, block selected
  → Tweak the block
  → Press "▶ Run" again
```

### Data model mapping (backend)

Existing `WorkflowSchema` → `AppModel`
- `name` → same
- `nodes` → still DAG of nodes, but internal types map to 4 external block types
- `edges` → simplified to data-flow only
- Execution modes removed (always EXECUTE)
- New field: `appType` (chat | analyzer | generator | email | custom)

Existing `Node` → `BlockNode`
- `type` maps: `source` → `receive`, `agent` → `think`, `output` → `act`, `memory` → `remember`
- Other types (condition, loop, guardrail, human, etc.) become **internal** — still work in the engine, invisible to the simplified UI
- `data.prompt` / `data.systemPrompt` → consolidated into NL description field
- `data.condition`, `data.loopCondition` → "Add condition" in Think block

### WebSocket events

Current: low-level technical events (progress, log, result, error, complete, metrics, wave, token, toolCall, iteration)

New: structured app-level events:
```json
{
  "type": "step",
  "stepIndex": 2,
  "blockId": "think-1",
  "blockType": "think",
  "label": "Analyzing request...",
  "status": "running",
  "details": "Determining intent: returning product",
  "duration": 0.3
}
```
```json
{
  "type": "live_update",
  "appType": "chat",
  "payload": {
    "messages": [
      {"role": "user", "content": "How do I return an item?"},
      {"role": "assistant", "content": "Let me check..."}
    ]
  }
}
```

## Error Handling

- **On Run:** If app has no Receive block → red border on canvas, tooltip "Your app needs an entry point"
- **On Run:** If blocks are disconnected → highlight orphaned blocks, "This block isn't connected to anything"
- **During Run:** Block error → block turns red on canvas, Timeline shows "❌ Think failed: OpenAI returned an error (rate limit)"
- **On Save:** Auto-save on every change, no save button needed

## Testing Strategy

1. **Blueprint mode:**
   - Block palette renders 4 types
   - Blocks can be dragged onto canvas
   - Connections snap between compatible blocks (any → any)
   - NL description field updates block behavior

2. **Live mode:**
   - Chat interface renders for chat-type apps
   - File upload renders for analyzer-type apps
   - Streaming responses display correctly
   - Switching between Blueprint/Live while running works

3. **Timeline mode:**
   - Events display in correct order
   - Clicking a step selects the block on Blueprint
   - Empty timeline shows "Run your app to see what happened"

4. **Dashboard:**
   - New app creation works (blank + template)
   - App cards show status correctly
   - Template selection creates correct initial graph

5. **Regression:**
   - Existing saved schemas load correctly (migration)
   - Backend execution engine unchanged — existing tests pass
   - Auth flow unchanged

## Removed Features

The following are removed from the UI (not from the engine — they still work via API):

- Execution mode selector (EXECUTE/ANALYZE/DRY_RUN — always EXECUTE now)
- RightPanel tabs (exec, plan, memory, history, templates)
- Left sidebar (schema list → replaced by Dashboard)
- OnboardingModal (replaced by Dashboard + template-first flow)
- CoachmarkOverlay (no longer needed)
- CommandPalette (Cmd+K — simplified)
- PlanPanel (plan management — moved to Settings or removed)
- MemoryPanel (Remember block replaces it)
- TemplateGallery (templates live on Dashboard)
- SchemaBuilderModal (NL block config replaces it)
- 12 internal node types hidden from UI
- Execution log panel (replaced by Timeline)

## Open Questions

1. **Migration:** What happens to existing saved schemas with complex node types (condition, loop, guardrail, human)? Option A: They open in "advanced mode" with the full canvas. Option B: They get flattened — internal nodes are hidden but functional.

2. **Live view extensibility:** How does the Live view know what UI to render for custom app types? We need a simple plugin model or a default fallback (generic I/O console).

3. **Timeline ↔ Blueprint navigation:** When clicking a Timeline step, we navigate to Blueprint and select the block. But what if the user has made changes to the Blueprint since that run? The Timeline is frozen — it shows what *was*, not what *is*.

4. **Tools in Think blocks:** Currently the UI has 15 tools (file_read, web_search, git, bash...). Should these be visible in the simplified UI or hidden behind "advanced"?

5. **Collaboration/Share:** The current app has share links and read-only views. Do these survive the redesign? If so, how does a shared app look in Live mode?
