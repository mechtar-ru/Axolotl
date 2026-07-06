cd /tmp && python3 -c "
with open('snake_check.py', 'r') as f:
    content = f.read()
print(f'File size: {len(content)} chars')
print(f'Line count: {content.count(chr(10))} lines')
print()
print('Last 100 chars:')
print(repr(content[-100:]))
"