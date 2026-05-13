# Roadmap: Adopting Agentic Harness Engineering (AHE) in Axolotl

## Overview

arXiv:2604.25850v2 ("Agentic Harness Engineering") presents a closed-loop system for evolving coding-agent harnesses via three observability pillars. This document maps AHE concepts to Axolotl architecture and defines implementation tasks.

**Target**: make Axolotl's harness self-evolvable using the evaluate→analyze→improve loop.

---

## AHE Theory Summary

| Pillar | What It Provides | AHE Mechanism |
|---|---|---|
| Component observability | Explicit, revertible action space | 7 component types as files (system prompt, tools, middleware, skills, sub-agents, long-term memory), git-tracked |
| Experience observability | Structured evidence from raw traces | Agent Debugger distills millions of tokens into root-cause reports |
| Decision observability | Falsifiable edit contracts | Change manifest + prediction → next-round verification + rollback |

**Core loop** (per iteration):

1. **Evaluate**: Run current harness against benchmark (harbor)
2. **Analyze**: Distill traces → evidence corpus (Agent Debugger)
3. **Improve**: Evolve Agent edits components, records prediction
4. **Verify**: Next round checks prediction → rollback if false

**Results reported**: pass@1 69.7% → 77.0% on Terminal-Bench 2 (10 iter), transfers to SWE-bench + cross-model.

---

## Axolotl Gap Analysis

### What Axolotl Already Has (partial)

| AHE Concept | Axolotl Equivalent | Status |
|---|---|---|
| Component = file | Schema nodes (source, agent, output, filewrite, command) | ✅ Implemented |
| Tool descriptions | Node config (tools[], permissions) | ✅ Implemented |
| System prompt | Agent node systemPrompt | ✅ Implemented |
| Execution trace | WebSocket execution logs | ✅ Implemented |
| Result persistence | node.data.result | ✅ Implemented (recent fix) |
| Long-term memory | MemPalace integration (output node) | ⚠️ Partial (output mode works, but no MemPalace write) |
| Git tracking | Backend git (for harness files) | ❌ Not integrated |

### What's Missing (must build)

| Gap | AHE Reference | Priority | Effort |
|---|---|---|---|
| 7-component decomposition | Component observability | P0 | High |
| Root-cause distillation from traces | Experience observability | P0 | High |
| Change manifest + prediction | Decision observability | P0 | Medium |
| Evolution meta-agent | Evolve Agent | P0 | High |
| Benchmark harness (harbor) | Evaluate step | P1 | Medium |
| Edit rollback at file granularity | Git-based revert | P1 | Medium |
| Cross-model transfer | Transfer learning | P2 | Low |

---

## Implementation Tasks (Numbering: Priority-Based)

### Phase 0: Human Pause System (Week 0-1) — HIGH PRIORITY
✅ COMPLETED 2026-05-04

### Phase 1: Component Observability (Week 1-2)
✅ COMPLETE 2026-05-04

#### Task 1.1: Decompose harness into 7 orthogonal components

**Goal**: make every tunable element a file at a known path.

**Components to extract** (new dir: `harness/`):

```
harness/
├── system_prompt.txt          # agent instructions
├── tool_descriptions.yaml   # tool specs (file_read, file_write, bash, ...)
├── tool_impl/              # custom tool code
│   ├── file_read.py
│   ├── file_write.py
│   └── bash.py
├── middleware/            # context compaction, fallback, loop detection
│   ├── context_compact.py
│   ├── failover.py
│   └── ralph_loop.py
├── skills/               # prompt engineering skills (from CLAUDE.md)
│   ├── Caveman.md
│   └── ...
├── sub_agents/           # nested agent configs
│   └── explore.yaml
└── memory/              # long-term memory (MemPalace)
    └── config.yaml
```

**Owner**: Sisyphus (this session)

**Tests**: each file loads independently; edits to one don't break others.

#### Task 1.2: Git-track harness directory

**Goal**: enable file-level diffs and rollbacks.

**Implementation**:

- Initialize git repo in `harness/` (or subdirectory of workspace)
- Each evolve iteration commits: `git commit -m "evolve: iter N - <prediction>"`
- Rollback: `git checkout <commit>^ -- <file>`

**Owner**: Sisyphus

**Tests**: `git log --oneline` shows iteration history; `git diff HEAD~1` shows delta.

---

### Phase 2: Experience Observability (Week 2-3)
✅ COMPLETE 2026-05-04

#### Task 2.1: Trace distillation pipeline

**Goal**: convert raw execution logs → root-cause evidence corpus.

**Input**: full WebSocket trace per schema execution (potentially MB).

**Output** (per task/run):

```json
{
  "task_id": "schema-xxx",
  "outcome": "PASS|FAIL",
  "root_cause": "file not found in /path",
  "tool_calls": [
    {"tool": "bash", "args": "...", "result": "error: ..."}
  ],
  "tokens_used": 1234,
  "duration_ms": 5432
}
```

**Implementation**:

1. Parse WebSocket messages: progress, result, error, complete
2. Extract tool call sequences from agent node execution
3. Identify failure points: first error in sequence
4. Aggregate into benchmark-level summary

**Storage**: `harness/evidence/{run_id}.jsonl`

**Owner**: Sisyphus

**Tests**: run schema → evidence JSON matches actual trace.

#### Task 2.2: Evidence API endpoint

**Goal**: expose distilled evidence to evolve agent.

**Endpoints**:

- `GET /api/evidence/{run_id}` — single run evidence
- `GET /api/evidence?schemaId={id}` — evidence for schema
- `GET /api/evidence/aggregate` — benchmark-level summary

**Owner**: Sisyphus

**Tests**: curl endpoint → valid JSON.

---

### Phase 3: Decision Observability (Week 3-4)
✅ COMPLETE 2026-05-04

#### Task 3.1: Change manifest schema

**Goal**: record each edit as falsifiable contract.

**Schema** (in `harness/manifest.yaml`):

```yaml
edits:
  - id: "evolve-001"
    type: "improvement"
    description: "Add retry for bash tool timeout"
    files: ["harness/middleware/retry.py"]
    prediction:
      fixes: ["bash-timeout-001", "bash-timeout-002"]
      regresses: []
    rationale: "Terminal-Bench logs show 3 bash timeouts"
    iter: 1
    status: "verified"  # pending|verified|rolled_back
```

**Implementation**:

- POST `/api/harness/evolve` — submit edit with prediction
- After next eval, auto-verify prediction against outcome
- Rollback on failure: `git revert` or manual

**Owner**: Sisyphus

**Tests**: submit edit → verify after run → status updates correctly.

#### Task 3.2: Evolve agent prompt

**Goal**: prompt that reads evidence and proposes component edits.

**Prompt structure** (system message):

```
You are the Evolve Agent. Your job is to improve the coding harness.

Rules:
- Read evidence from harness/evidence/*.jsonl
- Edit only files in harness/ folder
- For each edit, record what you expect to fix and what might regress
- Never edit files outside harness/

Current components:
- system_prompt.txt: base instructions
- tool_descriptions.yaml: available tools
- ...

Evidence summary (last run):
<evidence from Task 2.1>

Proposal format:
FILE: <path>
EDIT: <description>
PREDICT_FIXES: <task patterns>
PREDICT_REGRESSES: <task patterns>
RATIONALE: <why this component>
```

**Owner**: Sisyphus

**Tests**: evolve agent receives evidence → proposes valid edit.

---

### Phase 4: Full Loop Integration (Week 4-5)
✅ IN PROGRESS 2026-05-04

#### Task 4.1: Evolve loop API

**Goal**: orchestrate evaluate→analyze→improve as single API call.

**Endpoint**: `POST /api/harness/evolve`

**Parameters**:

- `schemaId`: schema to evaluate against
- `targetPassRate`: stop if pass@1 ≥ target
- `maxIterations`: hard limit
- `model`: LLM for evolve agent (default: same as execution)

**Loop**:

```python
for iter in range(maxIterations):
    # evaluate
    result = execute_schema(schemaId)
    evidence = distill_trace(result)
    
    # analyze
    summary = aggregate_evidence([evidence])
    
    # improve
    edit = evolve_agent.propose(summary, manifest)
    apply_edit(edit)
    commit(f"evolve: iter {iter} - {edit.prediction}")
    
    # verify (next iteration)
    if result.pass_rate >= targetPassRate:
        break
```

**Owner**: Sisyphus

**Tests**: call API → runs full loop → harness evolves.

#### Task 4.2: Dashboard / monitoring

**Goal**: visualize evolution progress.

**UI** (optional, frontend):

- Pass@1 over iterations (line chart)
- Edit history table
- Evidence inspector per run

---

## Unscheduled / Future (if time permits)

| Task | Description | Blocked By |
|---|---|---|
| Cross-model transfer | Test evolved harness on different models | Phase 4 |
| Benchmark integration (harbor) | Run against SWE-bench / Terminal-Bench | External |
| Guardrails | Prevent edit chaos | Phase 1-3 |

---

## Testing Strategy

### Unit Tests

- Each component file loads independently
- Evidence JSON matches trace
- Manifest schema valid

### Integration Tests

- Full evolve loop: baseline pass@1 → run evolve → improved pass@1
- Rollback: edit breaks → revert → baseline restores

### Acceptance Criteria

| Criterion | Metric |
|---|---|
| Component observability | 7 files in harness/ directory, git-tracked |
| Experience observability | evidence/ contains valid JSONL per run |
| Decision observability | manifest.yaml records predictions |
| Evolve loop functional | API runs without manual intervention |
| Improvement | pass@1 increases after 10 iterations |

---

## Risks & Mitigation

| Risk | Impact | Mitigation |
|---|---|---|
| Evidence distillation loses signal | Evolution makes bad edits | Keep raw traces for drill-down |
| Edit conflicts | Multiple edits overwrite | Git branch per iteration |
| Model drift | Evolve agent collapses | Max iterations cap |
| No improvement | Time wasted | Early stop on regression |

---

## References

- arXiv:2604.25850v2 — Agentic Harness Engineering
- GitHub: china-qijizhifeng/agentic-harness-engineering
- NexAU framework (component decomposition)
- Terminal-Bench 2, SWE-bench-verified (benchmarks)

---

*Generated*: 2026-05-03
*Author*: Sisyphus

---

## Additional Sections: Human Pause, Notifications, Playwright (Added 2026-05-03)

### RLM Implementation Status (arXiv 2512.24601v2)

**Status**: ✅ ALREADY IMPLEMENTED

The `rlm_predict` tool in ToolExecutor.java already implements the Recursive Language Model concept from arXiv:2512.24601v2.

```
Tool: rlm_predict
Description: Call sub-LM with DSPy signature for structured extraction
Parameters: signature, task, data
```

This tool enables recursive sub-LM calls for long context handling — no additional implementation needed.

---

### Phase 0: Human Pause System (Week 0-1) — HIGH PRIORITY

#### Task 0.1: Global Pause Button in ExecutionPanel

**Goal**: Allow user to pause schema execution mid-run.

**Implementation**:

- Add "⏸ Пауза" button to ExecutionPanel toolbar
- Backend: `POST /api/schemas/{id}/pause` — sets execution state to PAUSED
- Backend: `POST /api/schemas/{id}/resume` — continues execution
- WebSocket: emit `paused` event to frontend

**UI Location**: ExecutionPanel header, next to Stop button.

**Owner**: Sisyphus

**Tests**: start execution → pause → resume → complete → result correct.

#### Task 0.2: Runtime Prompt Editor (Mid-Run Modification)

**Goal**: Allow user to amend agent prompt while schema is running.

**Implementation**:

- Add "📝 Редактировать промт" button in ExecutionPanel (visible when paused or running)
- Opens modal/panel with editable systemPrompt for each AgentNode
- On save: `PUT /api/schemas/{id}/nodes/{nodeId}/prompt` — updates node.systemPrompt in memory
- Execution continues with new prompt on resume

**State Flow**: RUNNING → PAUSED (awaiting prompt edit) → RESUMED with new prompt.

**Owner**: Sisyphus

**Tests**: pause → edit prompt → resume → agent uses new prompt.

#### Task 0.3: Agent Tool `user_ask` — Request Human Input

**Goal**: Agent can request human input during execution.

**Tool Signature**:

```json
{
  "name": "user_ask",
  "description": "Ask user a question and wait for response",
  "parameters": {
    "type": "object",
    "properties": {
      "question": {"type": "string", "description": "Question for user"},
      "context": {"type": "string", "description": "Additional context"},
      "options": {"type": "array", "items": {"type": "string"}, "description": "Optional multiple choice"}
    },
    "required": ["question"]
  }
}
```

**Backend Implementation**:

- `ToolExecutor.java` adds `user_ask` handler
- Handler sends WebSocket message: `{type: "humanQuestion", question: "...", options: [...]}`
- Frontend shows modal with input field
- User response sent via: `POST /api/schemas/{id}/respond` → `{nodeId, response}`

**Two-Way Conversation**: Agent asks → user responds → agent continues with user input.

**Owner**: Sisyphus

**Tests**: agent calls `user_ask` → frontend shows question → user replies → agent receives response.

#### Task 0.4: Extend HumanNode for Global Schema Pause

**Goal**: Human node works during schema execution (not only as schema node).

**Implementation**:

- During execution, pause at any HumanNode globally (not just in schema)
- Frontend shows approval/feedback UI via ExecutionPanel (not just node)
- Same approve/reject mechanics, but accessible from panel

**Owner**: Sisyphus

---

### Phase 0.5: Notifications System (Week 1)

#### Task 0.5.1: Browser Toast Notifications

**Goal**: Notify user of pause requests, approval needed, execution complete.

**Implementation**:

- Extend WebSocket to emit `notification` event:
  ```json
  {
    "type": "notification",
    "title": "Требуется подтверждение",
    "body": "Агент запрашивает доступ к файлам",
    "action": "approve|reject|pause",
    "nodeId": "agent-1"
  }
  ```
- Frontend: show toast via existing toast system or add new toast component
- Categories: `human_pause`, `approval_needed`, `execution_complete`, `error`

**Owner**: Sisyphus

**Tests**: agent requests input → toast appears → user interacts.

#### Task 0.5.2: Webhook Notifications for Human Events

**Goal**: External systems receive human pause/approval events.

**Implementation**:

- Extend `/api/remote/webhooks` with new event types:
  - `human_pause_request` — execution paused, review needed
  - `human_approval_needed` — human node reached
  - `human_question` — agent asked user a question

**Webhook Payload**:

```json
{
  "event": "human_approval_needed",
  "schemaId": "...",
  "nodeId": "human-1",
  "prompt": "Подтвердите доступ к production DB?",
  "timestamp": "2026-05-03T12:00:00Z"
}
```

**Owner**: Sisyphus

**Tests**: webhook configured → trigger event → HTTP POST to webhook URL.

---

### Phase 0.6: Playwright MCP Integration (Week 1-2)

#### Task 0.6.1: Playwright MCP Server

**Goal**: Add MCP server for browser automation.

**Implementation**:

- Create new MCP server: `PlaywrightMcpServer.java`
- Endpoints: `POST /mcp/playwright` (JSON-RPC 2.0)
- Tools available:
  - `browser_navigate` — navigate to URL
  - `browser_click` — click selector
  - `browser_type` — type text
  - `browser_screenshot` — capture page
  - `browser_evaluate` — execute JS in browser context

**Tool Schema Example**:

```json
{
  "name": "browser_navigate",
  "description": "Navigate browser to URL",
  "parameters": {
    "type": "object",
    "properties": {
      "url": {"type": "string", "description": "URL to navigate to"},
      "wait": {"type": "number", "description": "Wait for selector after navigation"}
    },
    "required": ["url"]
  }
}
```

**Owner**: Sisyphus

**Tests**: MCP call `browser_navigate {url: "https://example.com"}` → page loads.

#### Task 0.6.2: Browser Automation Agent Tool

**Goal**: Expose Playwright as agent tool (alternative to MCP).

**Tool Name**: `browser_automate`

**Tool Signature**:

```json
{
  "name": "browser_automate",
  "description": "Automate browser actions (navigate, click, type, screenshot)",
  "parameters": {
    "type": "object",
    "properties": {
      "action": {"type": "string", "enum": ["navigate", "click", "type", "screenshot", "evaluate"]},
      "selector": {"type": "string", "description": "CSS selector"},
      "url": {"type": "string", "description": "URL for navigate"},
      "text": {"type": "string", "description": "Text to type"},
      "script": {"type": "string", "description": "JS to evaluate"}
    },
    "required": ["action"]
  }
}
```

**Implementation**: Add handler in ToolExecutor.java, call Playwright via HTTP or direct API.

**Comparison with Existing Web Tools**:

| Feature | web_search/web_fetch | browser_automate |
|---|---|---|
| HTTP GET | ✅ | ✅ |
| JavaScript rendering | ❌ | ✅ |
| Click/interaction | ❌ | ✅ |
| Screenshot | ❌ | ✅ |
| PDF extraction | ❌ | ✅ (via evaluate) |

**Owner**: Sisyphus

**Tests**: agent calls `browser_automate {action: "screenshot"}` → returns base64 image.

---

## Current Axolotl Feature Status

| Feature | Status | Notes |
|---|---|---|
| HumanNode (approve/reject) | ✅ Implemented | frontend/src/components/nodes/HumanNode.vue |
| RLM (rlm_predict tool) | ✅ Implemented | ToolExecutor.java — recursive sub-LM calls |
| MCP Server (Plan) | ✅ Implemented | /mcp endpoint — JSON-RPC 2.0 |
| Remote API webhooks | ✅ Implemented | /api/remote/webhooks |
| Global pause (schema) | ✅ Implemented | Task 0.1 — ExecutionPanel pause/resume |
| Runtime prompt edit | ✅ Implemented | Task 0.2 — amendedPrompt textarea |
| user_ask tool | ✅ Implemented | ToolExecutor + frontend question UI |
| Browser notifications | ✅ Implemented | Task 0.5.1 — WebSocket + toast |
| Human webhook events | ✅ Implemented | Task 0.5.2 — sendWebhook methods |
| Playwright MCP | ✅ Implemented | Task 0.6.1 — MCP server ready |
| browser_automate tool | ✅ Implemented | Task 0.6.2 — registered (placeholder) |
| Spring AI function calling | ✅ Implemented | SpringAiLlmProvider with toolSchemas |
| Model filter (local/custom) | ✅ Implemented | AgentNode + WorkflowCanvas filters |

*Updated: 2026-05-04*