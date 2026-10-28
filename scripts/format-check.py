#!/usr/bin/env python3
"""Minimal source hygiene check; no network or external formatter required."""
from pathlib import Path
import sys
root = Path(__file__).resolve().parent.parent
failures = []
for path in root.rglob('*'):
    if not path.is_file() or any(part in {'.git', '.tools', 'target'} for part in path.parts):
        continue
    if path.suffix not in {'.java', '.xml', '.yml', '.html', '.css', '.js', '.md', '.sh', '.py', '.txt'}:
        continue
    text = path.read_text()
    if not text.endswith('\n') or '\r' in text or any(line.rstrip() != line for line in text.splitlines()):
        failures.append(str(path.relative_to(root)))
if failures:
    print('Fix trailing whitespace, CRLF or missing final newline:', *failures, sep='\n')
    sys.exit(1)
print('Source hygiene passed.')
