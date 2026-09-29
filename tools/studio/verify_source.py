#!/usr/bin/env python3
"""Verify the additive Studio source inventory and retain the original 10015 guard."""
from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]

def main():
    manifest = json.loads((ROOT / 'tools/studio/inventory.json').read_text())
    paths = manifest['files']
    if manifest['baseCommit'] != '0796957f216fe1b69e094660f7aef496be110177':
        raise RuntimeError('Unreviewed Studio source base')
    for name, expected in paths.items():
        p = ROOT / name
        if Path(name).is_absolute() or '..' in Path(name).parts or p.is_symlink() or not p.is_file():
            raise RuntimeError('Invalid Studio inventory path: ' + name)
        if hashlib.sha256(p.read_bytes()).hexdigest() != expected:
            raise RuntimeError('Studio source mismatch; preserve intervening edit: ' + name)
    spec = importlib.util.spec_from_file_location('original_guard', ROOT / 'tools/completion10018/materialize.py')
    guard = importlib.util.module_from_spec(spec); spec.loader.exec_module(guard)
    protected = guard.protect()
    qa = ROOT / 'qa'; qa.mkdir(exist_ok=True)
    (qa/'source-paths.txt').write_text('\n'.join(sorted(paths))+'\n')
    (qa/'source-materialization.json').write_text(json.dumps({
        'baseline': guard.BASE, 'studioBase': manifest['baseCommit'], 'sourceFiles': paths,
        'protectedFiles': protected, 'phoneAccepted': False,
        'inheritedChatterboxAcceptance': 'unresolved; this addition does not clear it'
    }, indent=2))
    print('Verified', len(paths), 'Studio/inherited source files and', protected, 'protected 10015 files')

if __name__ == '__main__': main()
