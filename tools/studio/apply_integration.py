#!/usr/bin/env python3
"""Apply the readable, hash-bound edits once; never overwrite an unknown revision."""
from pathlib import Path
import hashlib
import json
ROOT = Path(__file__).resolve().parents[2]
def digest(data): return hashlib.sha256(data).hexdigest()
def main():
    records = json.loads((ROOT/'tools/studio/integration.json').read_text())
    pending = []
    for record in records:
        name = record['path']; p = ROOT/name
        if name not in ('unified/src/main/java/com/rosalina/unified/Session.kt',
                        'unified/src/main/java/com/rosalina/unified/MainActivity.kt',
                        'tools/completion10018/materialize.py'):
            raise RuntimeError('Unapproved integration path')
        if p.is_symlink() or not p.is_file(): raise RuntimeError('Missing integration source: '+name)
        old = p.read_bytes(); current = digest(old)
        if current == record['after']: continue
        if current != record['before']: raise RuntimeError('Intervening source edit preserved: '+name)
        lines = old.decode('utf-8').splitlines(keepends=True); end = 0
        for a,b,text in record['edits']:
            if not end <= a <= b <= len(lines): raise RuntimeError('Overlapping integration edit')
            end = b
        for a,b,text in reversed(record['edits']): lines[a:b] = text.splitlines(keepends=True)
        result = ''.join(lines).encode('utf-8')
        if digest(result) != record['after']: raise RuntimeError('Integration checksum mismatch: '+name)
        pending.append((p,result))
    for p,data in pending: p.write_bytes(data)
    from verify_source import main as verify
    verify()
    print('Applied',len(pending),'integration files')
if __name__ == '__main__': main()
