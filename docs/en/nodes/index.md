---
title: Nodes Overview
description: Overview of all node types in Axolotl
---

# Nodes

Axolotl provides 12 types of nodes to build AI workflows. Each node has a specific purpose.

## Node Types

### Input / Output

| Node | Description |
|------|-------------|
| [Source](/en/nodes/source) | Data input for the workflow |
| [Output](/en/nodes/output) | Results output handler |
| [Human](/en/nodes/human) | Manual approval gate |

### AI / Logic

| Node | Description |
|------|-------------|
| [Agent](/en/nodes/agent) | LLM call with model selection |
| [Condition](/en/nodes/condition) | Branch logic (if/else) |
| [Loop](/en/nodes/loop) | Iteration with max iterations |

### Memory / Context

| Node | Description |
|------|-------------|
| [Memory](/en/nodes/memory) | MemPalace integration |
| [Subagent](/en/nodes/subagent) | Nested workflow call |

### Safety / Utilities

| Node | Description |
|------|-------------|
| [Guardrail](/en/nodes/guardrail) | Data validation |
| [Fallback](/en/nodes/fallback) | Error handling |
| [Group](/en/nodes/group) | Node grouping |
| [SchemaBuilder](/en/nodes/schemabuilder) | AI-driven generation |

## Common Properties

All nodes share these properties:

- **Name** - Custom name (double-click to edit)
- **Position** - X/Y coordinates on canvas
- **Status** - idle, running, completed, failed

## Input / Output Ports

Nodes connect via typed ports:

- **Top port** - Target (input)
- **Bottom port** - Source (output)
- **Edges** can be typed: `data`, `condition_true`, `condition_false`, `loop`

## Next Steps

Start with the core nodes:
- [Source](/en/nodes/source) - Input data
- [Agent](/en/nodes/agent) - LLM call
- [Output](/en/nodes/output) - Results
- [Condition](/en/nodes/condition) - Logic