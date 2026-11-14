#!/usr/bin/env python3
"""Inspect every staged ELF dependency before signing; never substitute TCG."""
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import zipfile

root, protected, out = map(Path, sys.argv[1:])
libs = {p.name: p for p in root.glob('qube-android-*/src/main/jniLibs/arm64-v8a/*.so')}
platform = {'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libz.so',
            'libEGL.so','libGLESv2.so','libGLESv1_CM.so','libOpenSLES.so',
            'libjnigraphics.so','libvulkan.so','libaaudio.so','libstdc++.so'}
report = []
for name, path in sorted(libs.items()):
    header = subprocess.check_output(['readelf','-h',str(path)], text=True)
    assert 'AArch64' in header, name
    dynamic = subprocess.check_output(['readelf','-d',str(path)], text=True)
    needed = set(re.findall(r'Shared library:\s*\[([^\]]+)\]',dynamic))
    assert not needed - libs.keys() - platform, (name, needed - libs.keys() - platform)
    report.append(f'{name}: NEEDED={sorted(needed)} SHA256={hashlib.sha256(path.read_bytes()).hexdigest()}')
core = libs['libwinjit-core-android-aarch64.so']
symbols = subprocess.check_output(['readelf','--dyn-syms','--wide',str(core)],text=True)
exports = set(re.findall(r'\b(?:FUNC|OBJECT)\s+(?:GLOBAL|WEAK)\s+DEFAULT\s+\d+\s+(\w+)',symbols))
required = {'hv_machine_init','hv_machine_free','hv_machine_add_ram','hv_cpu_reset_index',
            'hv_run_one','hv_machine_kick_all','winjit_cpu_from_qemu_state',
            'winjit_cpu_to_qemu_state','winjit_bus_install','winjit_bus_failures'}
assert required <= exports, required - exports
with zipfile.ZipFile(protected) as z:
    old = {Path(n).name for n in z.namelist() if n.startswith('lib/arm64-v8a/') and n.endswith('.so')}
    assert old <= libs.keys(), ('Original runtime library removed', old-libs.keys())
    # Native dependencies other than the two scoped engines remain exact.
    for name in old - {'libqemu-system-aarch64.so'}:
        assert z.read('lib/arm64-v8a/'+name) == libs[name].read_bytes(), name
(out/'NATIVE-DEPENDENCIES.txt').write_text('\n'.join(report)+'\n')
print('PASS: real WinJIT symbols, AArch64 ELFs, complete dependency closure and unchanged supporting runtime')
