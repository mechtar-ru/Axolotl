#!/usr/bin/env python3
"""
Playwright MCP Server — stdio transport, JSON-RPC 2.0 protocol.
Spawnable by opencode as an MCP tool server.

Usage:
  python3 server.py
  # Reads JSON-RPC 2.0 requests from stdin, writes responses to stdout

Protocol:
  Request:  {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"navigate","arguments":{"url":"..."}}}
  Response: {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"..."}]}}
"""

import sys
import json
from pathlib import Path

try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print("Installing playwright...", file=sys.stderr)
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "playwright", "-q"])
    subprocess.check_call([sys.executable, "-m", "playwright", "install", "chromium"])
    from playwright.sync_api import sync_playwright


class PlaywrightMCPServer:
    def __init__(self):
        self.playwright = None
        self.browser = None
        self.context = None
        self.page = None

    def start(self):
        self.playwright = sync_playwright().start()
        self.browser = self.playwright.chromium.launch(headless=True)
        self.context = self.browser.new_context(
            viewport={"width": 1280, "height": 720},
            user_agent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Axolotl-Harness/1.0",
        )
        self.page = self.context.new_page()

    def _text_result(self, text: str) -> dict:
        return {"content": [{"type": "text", "text": text}]}

    def _error_result(self, message: str) -> dict:
        return {"content": [{"type": "text", "text": f"Error: {message}"}], "isError": True}

    def handle_tool(self, name: str, args: dict) -> dict:
        try:
            match name:
                case "navigate":
                    url = args.get("url", "")
                    self.page.goto(url, wait_until="domcontentloaded")
                    return self._text_result(f"Navigated to {url}")

                case "click":
                    selector = args.get("selector", "")
                    self.page.click(selector)
                    return self._text_result(f"Clicked {selector}")

                case "fill":
                    selector = args.get("selector", "")
                    text = args.get("text", "")
                    self.page.fill(selector, text)
                    return self._text_result(f"Filled {selector}")

                case "screenshot":
                    self.page.wait_for_load_state("networkidle")
                    png = self.page.screenshot(full_page=args.get("fullPage", False))
                    encoded = base64.b64encode(png).decode("utf-8")
                    return {
                        "content": [
                            {"type": "text", "text": f"Screenshot ({len(png)} bytes)"},
                            {"type": "image", "data": encoded, "mimeType": "image/png"},
                        ]
                    }

                case "get_text":
                    selector = args.get("selector", "")
                    parts = self.page.locator(selector).all_text_contents()
                    text = "\n".join(parts) if parts else ""
                    return self._text_result(text)

                case "evaluate":
                    script = args.get("script", "")
                    result = self.page.evaluate(script)
                    return self._text_result(json.dumps(result, indent=2, default=str))

                case "get_html":
                    selector = args.get("selector", "html")
                    html = self.page.inner_html(selector)
                    return self._text_result(html)

                case "go_back":
                    self.page.go_back()
                    return self._text_result("Went back")

                case "go_forward":
                    self.page.go_forward()
                    return self._text_result("Went forward")

                case _:
                    return self._error_result(f"Unknown tool: {name}")

        except Exception as e:
            return self._error_result(str(e))

    def stop(self):
        if self.page:
            self.page.close()
        if self.context:
            self.context.close()
        if self.browser:
            self.browser.close()
        if self.playwright:
            self.playwright.stop()


def run():
    import base64
    import argparse

    parser = argparse.ArgumentParser(description="Playwright MCP Server (stdio)")
    parser.add_argument("--port", type=int, default=3001, help="Unused; kept for compat")
    args = parser.parse_args()

    server = PlaywrightMCPServer()
    server.start()
    sys.stderr.write("Playwright MCP server started (stdio)\n")
    sys.stderr.flush()

    try:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                request = json.loads(line)
            except json.JSONDecodeError:
                continue

            req_id = request.get("id")
            method = request.get("method", "")
            params = request.get("params", {})

            if method == "tools/list":
                tools = [
                    {
                        "name": "navigate",
                        "description": "Go to a URL",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"url": {"type": "string"}},
                            "required": ["url"],
                        },
                    },
                    {
                        "name": "click",
                        "description": "Click an element by CSS selector",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"selector": {"type": "string"}},
                            "required": ["selector"],
                        },
                    },
                    {
                        "name": "fill",
                        "description": "Type text into a form field",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "selector": {"type": "string"},
                                "text": {"type": "string"},
                            },
                            "required": ["selector", "text"],
                        },
                    },
                    {
                        "name": "screenshot",
                        "description": "Capture page screenshot",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "fullPage": {"type": "boolean"}
                            },
                        },
                    },
                    {
                        "name": "get_text",
                        "description": "Get text content of element(s)",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"selector": {"type": "string"}},
                            "required": ["selector"],
                        },
                    },
                    {
                        "name": "evaluate",
                        "description": "Run JavaScript in page context",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"script": {"type": "string"}},
                            "required": ["script"],
                        },
                    },
                    {
                        "name": "get_html",
                        "description": "Get inner HTML of element",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"selector": {"type": "string"}},
                        },
                    },
                    {
                        "name": "go_back",
                        "description": "Go back in browser history",
                        "inputSchema": {"type": "object", "properties": {}},
                    },
                    {
                        "name": "go_forward",
                        "description": "Go forward in browser history",
                        "inputSchema": {"type": "object", "properties": {}},
                    },
                ]
                response = {"jsonrpc": "2.0", "id": req_id, "result": {"tools": tools}}

            elif method == "tools/call":
                tool_name = params.get("name", "")
                tool_args = params.get("arguments", {})
                result = server.handle_tool(tool_name, tool_args)
                response = {"jsonrpc": "2.0", "id": req_id, "result": result}

            elif method == "initialize":
                response = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "result": {
                        "protocolVersion": "0.1.0",
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "playwright-mcp", "version": "1.0.0"},
                    },
                }

            else:
                response = {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": f"Method not found: {method}"},
                }

            sys.stdout.write(json.dumps(response) + "\n")
            sys.stdout.flush()

    except KeyboardInterrupt:
        pass
    finally:
        server.stop()


if __name__ == "__main__":
    run()
