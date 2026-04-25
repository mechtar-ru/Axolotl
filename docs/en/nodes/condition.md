---
title: Condition Node
description: Branch logic node for Axolotl workflows
---

# Condition Node

The **Condition** node creates branching logic in your workflow based on evaluation of the input.

## Overview

```
┌─────────────────┐
│    Condition    │
│                 │
│ if {{input}}    │
│   contains     │
│ "error"?        │
│                 │
│ 🔽 True    🔽 False
└─────────────────┘
```

## Configuration

| Property | Type | Description |
|----------|------|-------------|
| **name** | string | Node name |
| **condition** | string | Condition expression |
| **conditionType** | string | contains, equals, matches, custom |

## Condition Types

| Type | Description | Example |
|------|-------------|---------|
| **contains** | Check if substring exists | `"error"` in `{{input}}` |
| **equals** | Exact match | `{{input}}` == `"yes"` |
| **matches** | Regex pattern | `{{input}}` matches `\d+` |
| **custom** | JavaScript expression | `{{input}}.length > 10` |

## Usage

1. Add a Condition node
2. Connect input from a previous node
3. Write your condition expression
4. Connect **True** output for when condition matches
5. Connect **False** output for when condition doesn't match

## Example

```javascript
// Condition: Check if input contains "urgent"
{{input}} contains "urgent"

// If true → Send notification
// If false → Send standard message
```

## Multiple Conditions

For complex logic, chain multiple Condition nodes:

```
Source → Agent → Condition
                  ├─ true → Notification Agent
                  └─ false → Normal Agent
```

## Error Handling

Use Conditions to detect errors:

```javascript
// Check for error indicators
{{input}} contains "error"

├─ true → Fallback node
└─ false → Continue processing
```

## Related Nodes

- [Loop](/en/nodes/loop) - Iteration logic
- [Guardrail](/en/nodes/guardrail) - Data validation
- [Fallback](/en/nodes/fallback) - Error handling