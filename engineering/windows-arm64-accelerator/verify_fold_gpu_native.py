#!/usr/bin/env python3
"""Verify 70120 staged Android QEMU+JNI natives, source/protected signers.

Only two specifically approved shared libraries may differ from protected
70114: libqemu-system-aarch64.so and libqube.so. Everything else byte-identical.
"""
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import zipfile

root,protected,out,native=map(Path,sys.argv[1:])
libs={p.name:p for p in root.glob('qube-android-*/src/main/jniLibs/arm64-v8a/*.so')}
platform={'libc.so','libm.so','libdl.so','liblog.so','libandroid.so','libz.so',
          'libEGL.so','libGLESv2.so','libGLESv1_CM.so','libOpenSLES.so',
          'libjnigraphics.so','libvulkan.so','libaaudio.so','libstdc++.so'}
report=[]
for name,path in sorted(libs.items()):
    elf=subprocess.check_output(['readelf','-h',str(path)],text=True)
    assert 'AArch64' in elf,name
    dynamic=subprocess.check_output(['readelf','-d',str(path)],text=True)
    needed=set(re.findall(r'Shared library:\s*\[([^\]]+)\]',dynamic))
    assert not needed-libs.keys()-platform,(name,needed-libs.keys()-platform)
    report.append(f'{name} SHA256={hashlib.sha256(path.read_bytes()).hexdigest()} NEEDED={sorted(needed)}')
with zipfile.ZipFile(protected) as z:
    old={Path(n).name for n in z.namelist()
         if n.startswith('lib/arm64-v8a/') and n.endswith('.so')}
    assert old<=libs.keys(),('Lost baseline native libraries',old-libs.keys())
    for name in old-{'libqemu-system-aarch64.so','libqube.so'}:
        assert z.read('lib/arm64-v8a/'+name)==libs[name].read_bytes(),f'Unexpected other native change: {name}'
for name in ['libqemu-system-aarch64.so','libqube.so']:
    assert libs[name].read_bytes()==(native/name).read_bytes(),f'Not the real source-built Fold native: {name}'
qemu=subprocess.check_output(['readelf','--dyn-syms','--wide',str(libs['libqemu-system-aarch64.so'])],text=True)
jni=subprocess.check_output(['readelf','--dyn-syms','--wide',str(libs['libqube.so'])],text=True)
core=subprocess.check_output(['readelf','--dyn-syms','--wide',str(libs['libwinjit-core-android-aarch64.so'])],text=True)
assert 'qube_gfx_request_fold_display' in qemu
assert 'Java_com_max2idea_android_qube_jni_QubeGfx_nativeRequestFoldGpuDisplay' in jni
for x in ['hv_machine_init','hv_cpu_reset_index','hv_run_one','winjit_cpu_from_qemu_state']:
    assert x in core,x
out.mkdir(parents=True,exist_ok=True)
(out/'NATIVE-DEPENDENCIES.txt').write_text('\n'.join(report)+'\n')
print('PASS: exact Android ARM64 Fold JNI + QEMU source binaries and WinJIT; all other native libs preserved')
