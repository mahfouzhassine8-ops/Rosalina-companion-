#!/usr/bin/env python3
"""The user's 2026-09-28 scope correction applies to both unified worker variants.
Legacy locked source must remain untouched, but must not be linked into Unified.
"""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent

class NoParentDeathGuardTest(unittest.TestCase):
    def test_unified_targets_do_not_link_legacy_guard(self):
        cmake = (ROOT / 'unified-native/CMakeLists.txt').read_text()
        self.assertNotIn('android_lifecycle.cpp', cmake)
        self.assertNotIn('follow_app_lifetime', cmake)

    def test_derived_workers_do_not_install_parent_death_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            subprocess.run([sys.executable, str(ROOT / 'unified-native/prepare.py'), directory], check=True)
            for engine in ('image', 'motion'):
                source = (Path(directory) / f'{engine}.cpp').read_text()
                self.assertNotIn('PR_SET_PDEATHSIG', source)
                self.assertNotIn('follow_app_lifetime', source)
                self.assertNotIn('getppid(', source)

    def test_explicit_stop_cleanup_remains(self):
        worker = (ROOT / 'unified/src/main/java/com/rosalina/unified/NativeWorker.kt').read_text()
        self.assertIn('p.destroy()', worker)
        self.assertIn('p.destroyForcibly()', worker)
        self.assertIn('TimeUnit.MILLISECONDS', worker)
        self.assertIn('redirectOutput(log)', worker)

if __name__ == '__main__':
    unittest.main()
