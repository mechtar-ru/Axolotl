---
title: Agent Node
description: LLM call node for Axolotl workflows
---

# Agent Node

The **Agent** node is the core of Axolotl. It calls Large Language Models to process and transform data.

## Overview

```
┌─────────────────┐
│      Agent      │
│    🤖  GPT-4   │
│  ┌───────────┐  │
│  │  Prompt   │  │
│  └───────────┘  │
│ 🔽 Output       │
└─────────────────┘
```

## Configuration

| Property | Type | Description |
|----------|------|-------------|
| **name** | string | Node name |
| **userPrompt** | string | User prompt template |
| **model** | string | LLM model selection |
| **systemPrompt** | string | (optional) System instructions |

## Supported Providers

| Provider | Models |
|----------|--------|
| **OpenAI** | GPT-4o, GPT-4o-mini, GPT-4 Turbo |
| **Anthropic** | Claude Sonnet, Claude Opus, Claude Haiku |
| **Ollama** | Any local model (llama3, mistral, etc.) |
| **DeepSeek** | DeepSeek Chat |
| **Cloudflare** | Workers AI (kimi-k2.6, etc.) |
| **Custom** | Any OpenAI-compatible endpoint |

## Variable Interpolation

Use `{{variables}}` in prompts:

```javascript
// Example prompt with variables:
"{{input}}"

"Summarize this: {{input}}"

"You are a code reviewer. Review this code:\n{{input}}\n\nFocus on: {{focus}}"
```

Available variables:
- `{{input}}` - Output from previous node
- `{{prev_result}}` - Last result
- `{{node:Name}}` - Output from specific node

## Streaming

Enable token-by-token streaming for real-time LLM output display.

## Usage

1. Add an Agent node
2. Connect input from Source or another node
3. Double-click to open prompt editor
4. Select model from dropdown
5. Write your prompt (use `{{input}}` for previous result)
6. Execute the workflow

## Next Node

Agent output typically connects to:
- [Output](/en/nodes/output) - Display results
- [Agent](/en/nodes/agent) - Chain another LLM call
- [Condition](/en/nodes/condition) - Branch based on response