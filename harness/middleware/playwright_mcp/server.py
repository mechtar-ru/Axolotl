#!/usr/bin/env python3
"""
Playwright MCP Server - spawnable browser automation
Usage: python3 playwright_mcp.py [port]
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
        self.context = self.browser.new_context()
        self.page = self.context.new_page()
    
    def handle_tool(self, tool_name, args):
        result = {"ok": True, "data": ""}
        
        try:
            match tool_name:
                case "navigate":
                    url = args.get("url", "")
                    self.page.goto(url)
                    result["data"] = f"Navigated to {url}"
                
                case "click":
                    selector = args.get("selector", "")
                    self.page.click(selector)
                    result["data"] = f"Clicked {selector}"
                
                case "type":
                    selector = args.get("selector", "")
                    text = args.get("text", "")
                    self.page.fill(selector, text)
                    result["data"] = f"Typed into {selector}"
                
                case "screenshot":
                    path = args.get("path", "/tmp/screenshot.png")
                    self.page.screenshot(path=path)
                    result["data"] = f"Screenshot saved to {path}"
                
                case "evaluate":
                    script = args.get("script", "")
                    result["data"] = self.page.evaluate(script)
                
                case "get_text":
                    selector = args.get("selector", "")
                    result["data"] = self.page.text_content(selector)
                
                case "go_back":
                    self.page.go_back()
                    result["data"] = "Went back"
                
                case "go_forward":
                    self.page.go_forward()
                    result["data"] = "Went forward"
                
                case _:
                    result["ok"] = False
                    result["data"] = f"Unknown tool: {tool_name}"
        except Exception as e:
            result["ok"] = False
            result["data"] = str(e)
        
        return result
    
    def stop(self):
        if self.page:
            self.page.close()
        if self.context:
            self.context.close()
        if self.browser:
            self.browser.close()
        if self.playwright:
            self.playwright.stop()


def run_mcp():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=3001)
    args = parser.parse_args()
    
    server = PlaywrightMCPServer()
    server.start()
    print(f"Playwright MCP started on port {args.port}", file=sys.stderr)
    
    try:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            
            try:
                request = json.loads(line)
                tool = request.get("tool", "")
                args_data = request.get("args", {})
                
                result = server.handle_tool(tool, args_data)
                print(json.dumps(result))
                sys.stdout.flush()
            except json.JSONDecodeError:
                print(json.dumps({"ok": False, "data": "Invalid JSON"}))
                sys.stdout.flush()
    except KeyboardInterrupt:
        pass
    finally:
        server.stop()


if __name__ == "__main__":
    run_mcp()