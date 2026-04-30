#!/usr/bin/env python3
"""Axolotl API helper for harnesses. Usage:
  python3 scripts/api.py GET  /api/plan
  python3 scripts/api.py POST /api/plan/tasks '{"title":"Task"}'
  python3 scripts/api.py PUT  /api/plan/tasks/ID/status '{"status":"DONE"}'
"""
import json, sys, urllib.request, urllib.parse

BASE = "http://localhost:8082"
USER, PWD = "tech", "tech"

def get_token():
    data = json.dumps({"username": USER, "password": PWD}).encode()
    req = urllib.request.Request(f"{BASE}/api/auth/login", data=data,
                                 headers={"Content-Type": "application/json"})
    resp = urllib.request.urlopen(req)
    return json.loads(resp.read())["token"]

def call(method, path, body=None):
    token = get_token()
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(f"{BASE}{path}", data=data,
                                 method=method,
                                 headers={"Authorization": f"Bearer {token}",
                                          "Content-Type": "application/json"})
    try:
        resp = urllib.request.urlopen(req)
        print(resp.read().decode())
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read().decode()}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    method, path = sys.argv[1], sys.argv[2]
    body = json.loads(sys.argv[3]) if len(sys.argv) > 3 else None
    call(method, path, body)
