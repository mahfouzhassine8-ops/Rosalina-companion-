#!/usr/bin/env python3
"""Prove actual native QEMU + Android JNI GPU bridge build before APK packaging."""
import json
import os
from pathlib import Path
import subprocess

repo=os.environ['GITHUB_REPOSITORY']
root=Path('/tmp/windowsmode-out')
root.mkdir(parents=True,exist_ok=True)
def api(path):
    return json.loads(subprocess.check_output(['gh','api',f'repos/{repo}/{path}'],text=True))
def run_info(run_id,branch,workflow):
    r=api(f'actions/runs/{run_id}')
    assert r['head_branch']==branch,(run_id,r['head_branch'])
    assert r['path']=='.github/workflows/'+workflow,(run_id,r['path'])
    assert r['status']=='completed' and r['conclusion']=='success',r['conclusion']
    return {key:r[key] for key in ['id','html_url','head_sha','conclusion','path']}

native=run_info(os.environ['QEMU_RUN'],'windows-mode-70122-performance-research',
                'windows-arm64-70122-performance-native.yml')
core=run_info(os.environ['CORE_RUN'],'windows-arm64-qemu-accel-research',
              'windows-arm64-winjit-core-ndk.yml')
smoke=run_info(os.environ['SMOKE_RUN'],'windows-arm64-qemu-accel-research',
               'windows-arm64-qemu-winjit-boot-smoke.yml')
required=[
    'Build full native WinJIT ARM64 translator shared library on Linux',
    'Compile full QEMU system emulator with actual -accel winjit vCPU backend',
    'Boot ROM microguest through real QEMU -accel winjit and verify UART',
    'Execute guest EL1-to-EL0 exception return and user UART (NO WINDOWS)',
    'Verify 4KB EL1 page table and MMU enable in real QEMU WinJIT',
    'Differential-test TCR/MAIR sysreg writes via stolen and normal GPRs',
    'Differential-test guest memory loads into ARM64 TCR and MAIR',
    'Differential-test QEMU generic timer IRQ and WFI wakeup through GICv2',
]
jobs=api('actions/runs/'+os.environ['SMOKE_RUN']+'/jobs?per_page=100')['jobs']
assert len(jobs)==1
steps={s['name']:s['conclusion'] for s in jobs[0]['steps']}
assert all(steps.get(k)=='success' for k in required),steps
# No changes to the WinJIT core and vCPU accelerator sources vs their passed gates.
original=['qemu_arm_snapshot.c','qemu_phys_io.c','qemu_winjit_accel.c',
          'winjit_cpu_bridge.h','winjit_phys_bus.h','patch_qemu_virt_winjit.py']
subprocess.run(['git','diff','--exit-code',native['head_sha'],'HEAD','--']+
  ['engineering/windows-arm64-accelerator/'+p for p in original],check=True)
# The generated UI/render optimization source must equal the source used by
# the successful QEMU+JNI Android library build, not merely a compatible ABI.
subprocess.run(['git','diff','--exit-code',native['head_sha'],'HEAD','--',
    'engineering/windows-arm64-accelerator/stage_windows_boot_performance.py',
    'engineering/windows-arm64-accelerator/test_windows_boot_performance.py',
    'engineering/windows-arm64-accelerator/stage_qge_fold_gpu_bridge.py'], check=True)
root.joinpath('NATIVE-GATE-PROVENANCE.json').write_text(json.dumps({
    'QEMU_AND_ANDROID_JNI':native,'WINJIT_CORE':core,'LINUX_MICROGUEST':smoke,
    'LINUX_MICROGUEST_STEPS':steps,
    'validation':'SOURCE/NATIVE ONLY; NO WIN11 GUEST OR DEVICE ACCEPTANCE',
},indent=2)+'\n')
print('PASS: 7.1.22 native QEMU/Android JNI probe and earlier WinJIT core/guest gates retained')
