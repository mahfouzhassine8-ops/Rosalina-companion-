#!/usr/bin/env python3
"""Require native build + actual QEMU execution before signing a TEST APK."""
import json
import os
from pathlib import Path
import subprocess

repo = os.environ['GITHUB_REPOSITORY']
out = Path('/tmp/windowsmode-out')
out.mkdir(parents=True, exist_ok=True)

def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{repo}/{path}'], text=True))

sources = {
    'QEMU_RUN': ('windows-arm64-qemu-winjit-vcpu.yml', [
        'qemu_arm_snapshot.c', 'qemu_phys_io.c', 'qemu_winjit_accel.c',
        'winjit_cpu_bridge.h', 'winjit_phys_bus.h', 'patch_qemu_virt_winjit.py']),
    'CORE_RUN': ('windows-arm64-winjit-core-ndk.yml', [
        'port-windowsios-core-android.py', 'jit_region_android.c',
        'winjit_cpu_bridge.h', 'winjit_cpu_bridge.c', 'winjit_phys_bus.h', 'winjit_phys_bus.c']),
}
evidence = {}
for key, (workflow, paths) in sources.items():
    run = api('actions/runs/' + os.environ[key])
    assert run['head_branch'] == 'windows-arm64-qemu-accel-research', run
    assert run['path'] == '.github/workflows/' + workflow, run['path']
    assert run['status'] == 'completed' and run['conclusion'] == 'success', (key, run['status'], run['conclusion'])
    subprocess.run(['git', 'diff', '--exit-code', run['head_sha'], 'HEAD', '--'] +
                   ['engineering/windows-arm64-accelerator/' + p for p in paths], check=True)
    evidence[key] = {k: run[k] for k in ['id', 'html_url', 'head_sha', 'conclusion', 'path']}

run = api('actions/runs/' + os.environ['SMOKE_RUN'])
assert run['status'] == 'completed', 'Wait for the real guest evidence upload'
assert run['head_branch'] == 'windows-arm64-qemu-accel-research'
assert run['path'] == '.github/workflows/windows-arm64-qemu-winjit-boot-smoke.yml'
# Full boot and production compatibility are FINAL-release gates. A later
# identity/production-profile failure does not erase a verified basic TEST gate.
required = {
    'Build full native WinJIT ARM64 translator shared library on Linux',
    'Compile full QEMU system emulator with actual -accel winjit vCPU backend',
    'Boot ROM microguest through real QEMU -accel winjit and verify UART',
    'Execute guest EL1-to-EL0 exception return and user UART (NO WINDOWS)',
    'Verify 4KB EL1 page table and MMU enable in real QEMU WinJIT',
    'Differential-test TCR/MAIR sysreg writes via stolen and normal GPRs',
    'Differential-test guest memory loads into ARM64 TCR and MAIR',
    'Differential-test QEMU generic timer IRQ and WFI wakeup through GICv2',
}
jobs = api('actions/runs/' + os.environ['SMOKE_RUN'] + '/jobs?per_page=100')['jobs']
assert len(jobs) == 1
steps = {s['name']: s['conclusion'] for s in jobs[0]['steps']}
assert all(steps.get(s) == 'success' for s in required), steps
for _, paths in sources.values():
    subprocess.run(['git', 'diff', '--exit-code', run['head_sha'], 'HEAD', '--'] +
                   ['engineering/windows-arm64-accelerator/' + p for p in paths], check=True)
evidence['SMOKE_RUN'] = {k: run[k] for k in ['id', 'html_url', 'head_sha', 'conclusion', 'path']}
evidence['SMOKE_RUN']['steps'] = steps
evidence['validation'] = 'TEST CANDIDATE — WinJIT/Windows boot not yet verified; NOT DEVICE-VERIFIED'
(out / 'NATIVE-GATE-PROVENANCE.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('PASS: current native sources compiled, linked, loaded and ran real QEMU guests without a crash')
