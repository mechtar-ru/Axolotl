# Node Types Reference

Axolotl supports five node types in the pipeline system. Each node type has a specific role and configuration options.

## Source (Receive)

Collects input for the pipeline. Supports four source modes:

| Mode | Description |
|------|-------------|
| `text` | Direct text input pasted in the config panel |
| `file` | File reference — reads a file relative to `targetPath` |
| `url` | URL fetch — downloads content from a URL |
| `project` | Project directory — lists files in `targetPath` up to configurable depth |

### Configuration

| Field | Type | Description |
|-------|------|-------------|
| `sourceType` | `"text" \| "file" \| "url" \| "project"` | Source mode |
| `sourceData` | `string` | Text content (text mode) |
| `filePath` | `string` | File path (file mode) |
| `url` | `string` | URL to fetch (url mode) |
| `projectPath` | `string` | Directory path (project mode) |
| `maxDepth` | `number` | Max directory depth (project mode) |
| `maxFiles` | `number` | Max files to list (project mode) |

## Agent

Tool-enabled LLM node for code generation. Uses the configured LLM provider with tools.

### Tools

| Tool | Description |
|------|-------------|
| `file_write` | Write content to a file in `targetPath` |
| `file_read` | Read a file |
| `directory_read` | List directory contents |
| `bash` | Execute a bash command (sandboxed) |
| `web` | Fetch a URL |
| `grep` | Search file contents |
| `glob` | Find files by pattern |

### Configuration

| Field | Type | Description |
|-------|------|-------------|
| `model` | `string` | LLM model name |
| `systemPrompt` | `string` | System prompt for the LLM |
| `userPrompt` | `string` | User prompt (supports `{{sourceData}}` template) |
| `agentType` | `"coder" \| "reviewer" \| "architect"` | Agent role |
| `enabledTools` | `string[]` | List of enabled tools |
| `maxTokens` | `number` | Max response tokens |
| `temperature` | `number` | LLM temperature (0-2) |

## Review

Generates a development plan and runs quality checks before code generation.

### Checks

| Check | Description |
|-------|-------------|
| **Premortem** | Anticipates what could go wrong and proposes mitigations |
| **Prism** | Analyzes the plan from multiple architectural perspectives |
| **Postmortem** | Evaluates the completed plan for gaps and improvements |

### Modes

| Mode | Behavior |
|------|----------|
| `manual` | Always shows approval dialog, waits for human decision |
| `auto` | Auto-fixes issues up to `maxIterations` (default 3), fails on exceed |
| `hybrid` | Auto-fixes up to `maxAutoIterations`, then shows human dialog |

### Configuration

| Field | Type | Description |
|-------|------|-------------|
| `premortem` | `boolean` | Enable premortem check |
| `prism` | `boolean` | Enable prism check |
| `postmortem` | `boolean` | Enable postmortem check |
| `mode` | `"manual" \| "auto" \| "hybrid"` | Review mode |
| `maxAutoIterations` | `number` | Max auto-fix iterations (hybrid mode) |
| `maxIterations` | `number` | Max total iterations (auto mode) |
| `generatePlan` | `boolean` | Generate a development plan |

## Verifier

Validates generated code against configurable criteria.

### Checks

| Check | Description |
|-------|-------------|
| `syntaxCheck` | Validates syntax for detected language |
| `requiredPatterns` | Checks for required code patterns (regex) |
| `testCommand` | Runs a test command and checks exit code |
| `maxFileSizeKb` | Rejects files exceeding size limit |

### Configuration

| Field | Type | Description |
|-------|------|-------------|
| `checks` | `object` | Enabled checks with parameters |
| `rewriteOnFail` | `boolean` | Auto-rewrite on verification failure |
| `maxRewriteRetries` | `number` | Max auto-rewrite attempts (default 3) |
| `validationCriteria` | `string` | Custom validation criteria for LLM |

The verifier produces a structured verdict:

```json
{
  "status": "PASS",
  "checks": [
    { "name": "syntax", "passed": true },
    { "name": "size", "passed": true }
  ],
  "summary": "All checks passed"
}
```

## Output

Collects and reports execution results.

### Modes

| Mode | Description |
|------|-------------|
| `stdout` | Prints results to console |
| `log` | Writes results to application log |
| `summary_report` | Generates `pipeline-report.md` in `targetPath` |

### Configuration

| Field | Type | Description |
|-------|------|-------------|
| `mode` | `"stdout" \| "log" \| "summary_report"` | Output mode |
| `reportPath` | `string` | Report file path (summary_report mode) |
| `includeReview` | `boolean` | Include review in report |
| `includeFiles` | `boolean` | Include file list in report |
| `includeVerification` | `boolean` | Include verification results in report |
| `includeMetrics` | `boolean` | Include execution metrics in report |
