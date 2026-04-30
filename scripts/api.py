#!/usr/bin/env python3
"""Axolotl API helper for harnesses. Usage:
  python3 scripts/api.py GET  /api/plan
  python3 scripts/api.py POST /api/plan/tasks '{"title":"Task"}'
  python3 scripts/api.py PUT /api/plan/tasks/ID/status @/tmp/body.json'
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

def load_body(body_arg):
    if body_arg is None:
        return None
    if body_arg.startswith('@'):
        with open(body_arg[1:], 'r') as f:
            return json.load(f)
    return json.loads(body_arg)

def call(method, path, body=None):
    token = get_token()
    encoded_path = urllib.parse.quote(path, safe=':/?&=', encoding='utf-8')
    data = json.dumps(body).encode('utf-8') if body else None
    req = urllib.request.Request(f"{BASE}{encoded_path}", data=data,
                                  method=method,
                                  headers={"Authorization": f"Bearer {token}",
                                           "Content-Type": "application/json; charset=utf-8"})
    try:
        resp = urllib.request.urlopen(req)
        print(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read().decode('utf-8')}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(1)
    method, path = sys.argv[1], sys.argv[2]
    body = load_body(sys.argv[3]) if len(sys.argv) > 3 else None
    call(method, path, body)
