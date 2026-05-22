# Sub-Agents

Sub-agents are specialized LLM agents that handle specific harness tasks.

## Available Sub-Agents

### test-executor
Runs Playwright E2E tests with Node.js, reports pass/fail per test.

### schema-verifier  
Validates WorkflowSchema JSON structure and pipeline configuration.

### api-tester
Hits Axolotl REST endpoints and validates responses.

## Quick Start

```bash
# Run a sub-agent against the running backend
opencode --agent test-executor --prompt "Run pipeline-review test"
```
