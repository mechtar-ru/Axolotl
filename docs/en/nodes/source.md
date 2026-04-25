---
title: Source Node
description: Data input node for Axolotl workflows
---

# Source Node

The **Source** node is the entry point for data into your workflow. It provides the initial input that subsequent nodes process.

## Overview

```
┌─────────────────┐
│      Source     │
│                 │
│  ┌───────────┐  │
│  │ Input text│  │
│  └───────────┘  │
│                 │
│      🔽 Output  │
└─────────────────┘
```

## Configuration

| Property | Type | Description |
|----------|------|-------------|
| **name** | string | Node name (double-click to edit) |
| **sourceData** | string | Input text or data |

## Usage

1. Add a Source node from the toolbar
2. Double-click to expand it
3. Enter your input text in the textarea
4. Connect the output port to another node's input

## Example

```javascript
// Source node content:
"Create a todo list for building a React app"

// Connected to Agent node → "Create a comprehensive plan"
// Connected to Output node → Display results
```

## Drag & Drop Files

You can also drag and drop text files onto the Source node to load their content.

## Next Node

The Source node output typically connects to:
- [Agent](/en/nodes/agent) - Process with LLM
- [Condition](/en/nodes/condition) - Branch based on content
- [Memory](/en/nodes/memory) - Store in MemPalace