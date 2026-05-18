#!/usr/bin/env python3
"""Update Бережно schema source node with design doc content."""
import json, sys, urllib.request, os

TOKEN = sys.argv[1] if len(sys.argv) > 1 else ''
BASE = 'http://localhost:8082/api'
SCHEMA_ID = 'd11110a6-92b4-48e0-9a95-c8df87abf93f'

# Read design doc
ideas_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '.ideas')
with open(os.path.join(ideas_dir, '002.md'), 'r') as f:
    design_content = f.read()

# Get current schema
req = urllib.request.Request(f'{BASE}/schemas/{SCHEMA_ID}',
    headers={'Authorization': f'Bearer {TOKEN}'})
with urllib.request.urlopen(req) as resp:
    schema = json.loads(resp.read())

nodes = schema.get('nodes', [])

# Find and update source node
for n in nodes:
    nid = n.get('id')
    data = n.get('data', {})
    
    if nid == 'n1':
        # Source node - add design doc
        data['sourceData'] = {'content': design_content}
        data['label'] = 'Бережно — Requirements'
        print(f'  {nid}: set sourceData.content ({len(design_content)} chars)')
    
    # Set labels for all nodes
    if nid == 'n2':
        data['label'] = 'Review & Approve Plan'
        data['agentType'] = 'REVIEW'
        print(f'  {nid}: set label={data["label"]} agentType={data["agentType"]}')
    elif nid == 'n3':
        data['label'] = 'Generate App Code'
        data['agentType'] = 'AGENT'
        print(f'  {nid}: set label={data["label"]} agentType={data["agentType"]}')
    elif nid == 'n4':
        data['label'] = 'Verify Build'
        data['agentType'] = 'VERIFIER'
        print(f'  {nid}: set label={data["label"]} agentType={data["agentType"]}')
    elif nid == 'n5':
        data['label'] = 'Write Output Files'
        print(f'  {nid}: set label={data["label"]}')

# Update schema via PUT
body = json.dumps({'nodes': nodes}).encode()
req = urllib.request.Request(f'{BASE}/schemas/{SCHEMA_ID}',
    data=body,
    headers={
        'Authorization': f'Bearer {TOKEN}',
        'Content-Type': 'application/json',
    },
    method='PUT')
with urllib.request.urlopen(req) as resp:
    updated = json.loads(resp.read())
    print(f'\nSchema "{updated.get("name")}" updated successfully')
    for n in updated.get('nodes', []):
        d = n.get('data', {})
        sd = d.get('sourceData', {})
        cl = len(sd.get('content', '')) if isinstance(sd, dict) else 0
        lbl = d.get('label', n.get('label', '?'))
        print(f'  {n["id"]}: type={n.get("type")} label="{lbl}" content_len={cl}')
