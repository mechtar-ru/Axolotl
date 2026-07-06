python3 << 'PYEOF'
code = """
...
waiting_for_input = Fa
"""
with open('/tmp/snake_final.py', 'w') as f:
    f.write(code)
print("Written OK, lines:", code.count('\n'))
PYEOF