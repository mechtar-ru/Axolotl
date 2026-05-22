# Middleware

Middleware components that run alongside the harness.

## Playwright MCP Server

Browser automation via stdio JSON-RPC 2.0 MCP protocol.

```bash
# Start the MCP server
python3 harness/middleware/playwright_mcp/server.py --port 3001

# Start via opencode
# Add to opencode.json MCP servers:
# {
#   "harness-playwright": {
#     "command": "python3",
#     "args": ["harness/middleware/playwright_mcp/server.py"]
#   }
# }
```

### Available Tools

| Tool | Description |
|------|-------------|
| `navigate` | Go to URL |
| `click` | Click element by selector |
| `fill` | Type text into field |
| `screenshot` | Capture page screenshot |
| `get_text` | Get element text content |
| `evaluate` | Run JS in page context |
| `go_back` / `go_forward` | Browser navigation |

## Node.js Version

An equivalent JS implementation is at `playwright_mcp/server.js`.
