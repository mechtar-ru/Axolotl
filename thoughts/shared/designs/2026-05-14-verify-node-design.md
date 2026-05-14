---
date: 2026-05-14
topic: "Verify Node — Add verification block type to Axolotl Studio"
status: draft
---

## Problem Statement

The Sokoban Game generation revealed a critical gap: the generated `.py` file had a missing player position (`@`) in the level definition, causing the game to crash on startup. The 3-node workflow (source → agent → output) has no verification step between generation and delivery. Generated code goes straight to disk without any syntax check, pattern validation, or test run.

The existing `guardrail` node type only does text-level LLM validation — it cannot execute `bash` commands, read files, or run syntax checks. We need a **verification block** that can actually execute verification actions using tools.

## Constraints

- Must work within the existing execution wave infrastructure (topological sort via Kahn's algorithm)
- Must use existing tool infrastructure (`file_read`, `bash`, `grep` tools already exist)
- Must NOT require new LLM provider or auth infrastructure
- Should follow existing block pattern (BlockPalette + BlockBase + VueFlow node type)
- Node failure must propagate to downstream nodes (block on FAIL)
- Must be representable in the schema JSON (importable/exportable)

## Approach

I'm making the `verifier` a **new node type** that reuses the agent's execution path with a structured verification context. A dedicated block provides clear visual differentiation from a Think (agent) node and a Guardrail node.

**Why not extend guardrail?** The guardrail is text-only LLM validation — it calls `llmService.chat()` with no tool context. Verification needs `file_read` + `bash` + `grep` tool execution. Mixing these into guardrail would make it a franken-node with two fundamentally different execution paths.

**Why not just use an agent node with a verification prompt?** An agent already CAN do this, but there's no visual affordance for verification in the palette, no config UI for verification rules, and no built-in result parsing for PASS/FAIL. A dedicated block makes verification an explicit, first-class concept.

## Architecture

### Data flow

```
[source] → [agent/generator] → [verifier] → [output]
                                    |
                          ┌─────────┴──────────┐
                          | 1. file_read file   |
                          | 2. bash py_compile  |
                          | 3. grep patterns    |
                          | 4. return PASS/FAIL |
                          └────────────────────┘
```

The verifier sits between generation and output. It receives the upstream result (file path/content), runs verification steps using tools, and outputs a structured verdict.

### Verdict format

The verifier returns structured JSON that downstream nodes and the UI can parse:

```json
{
  "status": "PASS",
  "checks": [
    {"name": "syntax", "passed": true},
    {"name": "required_patterns", "passed": true, "found": ["@", "move()", "check_victory"]},
    {"name": "test_command", "passed": true}
  ],
  "summary": "All 3 checks passed"
}
```

On failure:

```json
{
  "status": "FAIL",
  "checks": [
    {"name": "syntax", "passed": true},
    {"name": "required_patterns", "passed": false, "found": [], "missing": ["@"]},
    {"name": "test_command", "passed": false, "error": "Exit code 42"}
  ],
  "summary": "2/3 checks failed: missing player symbol, test failed"
}
```

If the result is FAIL, the verifier node sets its status to `FAILED`, which causes SchemaService to skip downstream nodes that depend on it (existing behavior — nodes with FAILED/BLOCKED status are excluded from the executable set).

## Components

### Frontend

#### 1. `VerifyBlock.vue` — new VueFlow node component

- Maps node type `verifier` to a visual block
- Purple color (`#8b5cf6`) for visual distinction from Think (blue), Act (orange), Receive (green)
- Shield/checkmark icon
- Extends `BlockBase.vue` like other blocks
- Shows PASS/FAIL status dot after execution

#### 2. BlockPalette update — add "Verify" entry

- Type: `verifier`
- Label: "Verify"
- Color: `#8b5cf6` (purple)
- Icon: shield with checkmark SVG path
- Order: between Think and Remember (or at the end)

#### 3. BlueprintView.vue — register verifier node type

- Add import for `VerifyBlock.vue`
- Add `verifier: VerifyBlock` to the node type mapping
- Add the verifier-compatible block types to the drop handler

#### 4. BlockConfigPanel.vue — verifier config section

When `blockType === 'verifier'`, show:

- **Checks section** — toggles for built-in check types:
  - ✓ Syntax check (runs the file through the language-specific compiler/interpreter)
  - ✓ Required patterns (textarea for required substrings/regex, one per line)
  - ✓ Test command (bash command string to run, e.g. `python3 -c "import sokoban"`)
  - ✓ Max file size (number input in KB)
- **Model selector** — model to use for the verification LLM call (needed for interpretation of results)
- **System prompt** — optional custom prefix to the verification system prompt
- No agentType selector (always `"verifier"`)
- Tools are not user-configurable (always `["file_read", "bash", "grep"]`)

#### 5. TimelineView.vue — verifier result display

- Show PASS/FAIL verdict in the timeline entry
- Color-coded: green for PASS, red for FAIL
- Expand to show per-check details

### Backend

#### 1. NodeExecutor.java — new `"verifier"` branch

Add after the `"guardrail"` branch:

```java
} else if ("verifier".equals(node.getType())) {
    result = executeVerifierNode(node, schemaId, resolvedModel);
}
```

#### 2. `executeVerifierNode()` method

1. Collects predecessor results (the generated file path/content from upstream)
2. Extracts verification rules from `node.getData().getConfig()`:
   - `syntaxCheck` (boolean)
   - `requiredPatterns` (list of strings)
   - `testCommand` (string)
   - `maxFileSizeKb` (number)
3. Builds a structured verification system prompt that instructs the LLM to:
   - Read the generated file using `file_read` tool
   - Run syntax check using `bash` tool
   - Search for required patterns using `bash grep` tool
   - Run any configured test command using `bash` tool
   - Return a structured PASS/FAIL verdict with per-check details
4. Calls the same execution path as `executeAgentNode()` with:
   - `agentType = "verifier"` (to distinguish in logs)
   - `enabledTools = ["file_read", "bash", "grep"]` (hardcoded)
   - `maxToolCalls = 10` (hardcoded, fewer than an agent since verification is targeted)
5. Parses the LLM result for `"status": "FAIL"` and sets node status to FAILED if found

#### 3. SchemaService — status propagation

The existing logic at line 347 already filters `BLOCKED`-status nodes from executable sets. We need to add the same filter for `FAILED`-status nodes (or rely on the existing behavior — need to verify).

If the verifier node fails, any downstream node with `think-1` → `verifier` → `act-1` will have `verifier` in FAILED state, and since `act-1` has an incoming edge from `verifier`, it won't be executable in the next wave.

#### 4. SchemaExporter — verifier Python export

Add a `case "verifier"` in `generatePythonForNode()` that exports a Python block:
- Reads the file from disk
- Runs `py_compile.compile()` for syntax check
- Runs `subprocess.run()` for test commands
- Prints structured pass/fail

## Data Model

The verifier node's `data.config` stores:

```json
{
  "checks": {
    "syntaxCheck": true,
    "requiredPatterns": ["@", "move()", "check_victory"],
    "testCommand": "python3 -m py_compile {{filepath}}",
    "maxFileSizeKb": 500
  }
}
```

The node's `agentType` is always `"verifier"` (not user-configurable).

The `enabledTools` are hardcoded to `["file_read", "bash", "grep"]` (not user-configurable).

## Error Handling

| Scenario | Behavior |
|----------|----------|
| File not found | Verifier reports FILE_NOT_FOUND, node status → FAILED |
| Syntax check fails | Verifier reports syntax errors with details, node status → FAILED |
| Required patterns missing | Verifier lists missing patterns, node status → FAILED |
| Test command fails | Verifier reports exit code + stderr, node status → FAILED |
| LLM call fails | Verifier node → FAILED with LLM error message |
| All checks pass | Verifier reports PASS, node status → COMPLETED |

## Testing Strategy

**Frontend:**
- Unit test VerifyBlock.vue renders with correct color/icon
- Unit test BlockPalette includes Verify entry
- Unit test BlockConfigPanel shows verification-specific fields
- Integration test: drop Verify block on canvas, configure checks, verify schema JSON

**Backend:**
- Unit test `executeVerifierNode()` with a known-valid file → PASS
- Unit test `executeVerifierNode()` with a file missing required pattern → FAIL
- Unit test `executeVerifierNode()` with invalid syntax → FAIL
- Unit test: verifier failure blocks downstream output node

**E2E:**
- Full workflow: source → agent(generate valid Python) → verifier → output → PASS
- Full workflow: source → agent(generate broken Python) → verifier → FAIL no output

## Open Questions

1. Should the verifier have a mode where it can FIX the issues it finds (like guardrail's `transform` mode)? — **No for v1.** That adds agentic self-healing complexity. v1 is detect-and-block only.

2. Should the requiredPatterns support regex or just substring match? — **Substring for v1.** Regex adds escaping complexity. We can add regex mode later.

3. What if there are multiple files to verify? — The config accepts a `filePath` parameter. If empty, the verifier reads from upstream result. If set, it verifies that specific path.

4. Should the verifier run checks sequentially or in parallel? — **Sequential** via LLM tool calls. The LLM decides the order based on the tool descriptions. This gives better error reporting (stop on first critical failure).
