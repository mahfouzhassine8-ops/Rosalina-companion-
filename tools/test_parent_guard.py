#!/usr/bin/env python3
"""Real Linux parent-death test; not a substitute for Samsung Force Stop testing."""
from pathlib import Path
import ctypes
import os
import select
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parent.parent

class ParentGuardTest(unittest.TestCase):
    def test_linked_for_every_android_worker_variant(self):
        source = (ROOT / 'unified-native/CMakeLists.txt').read_text()
        self.assertIn('foreach(ENGINE image motion)', source)
        self.assertIn('target_sources(rosalina-${ENGINE} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../motion-engine/android_lifecycle.cpp)', source)
        guard = (ROOT / 'motion-engine/android_lifecycle.cpp').read_text()
        self.assertIn('PR_SET_PDEATHSIG', guard)
        self.assertIn('SIGKILL', guard)
        self.assertIn('getppid() != parent', guard.replace('getppid()!=parent', 'getppid() != parent'))

    def test_explicit_stop_still_owns_reap(self):
        source = (ROOT / 'unified/src/main/java/com/rosalina/unified/NativeWorker.kt').read_text()
        for marker in ['redirectOutput(log)', 'p.destroy()', 'p.destroyForcibly()', 'TimeUnit.MILLISECONDS']:
            self.assertIn(marker, source)
        self.assertNotIn('p.inputStream.bufferedReader()', source)

    @unittest.skipUnless(sys.platform.startswith('linux'), 'Linux prctl test')
    def test_child_is_killed_when_parent_exits(self):
        libc = ctypes.CDLL(None, use_errno=True)
        self.assertEqual(0, libc.prctl(36, 1, 0, 0, 0))
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            main = directory / 'main.cpp'
            main.write_text('#include <cstdio>\n#include <unistd.h>\n#include <sys/prctl.h>\n#include <csignal>\nint main() { int installed=0; if(prctl(PR_GET_PDEATHSIG,&installed)!=0 || installed!=SIGKILL)return 2;\nstd::printf("%d\\n",getpid());std::fflush(stdout);for(;;)pause(); }\n')
            exe = directory / 'guard-fixture'
            subprocess.run(['c++', '-std=c++17', str(main), str(ROOT / 'motion-engine/android_lifecycle.cpp'), '-o', str(exe)], check=True)
            launcher = 'import subprocess,sys,time; p=subprocess.Popen([sys.argv[1]],stdout=subprocess.PIPE,text=True); print(p.stdout.readline().strip(),flush=True); time.sleep(30)'
            parent = subprocess.Popen([sys.executable, '-c', launcher, str(exe)], stdout=subprocess.PIPE, text=True)
            child = None
            try:
                ready, _, _ = select.select([parent.stdout], [], [], 5)
                self.assertTrue(ready, 'Worker did not report a ready PID')
                child = int(parent.stdout.readline().strip())
                parent.kill()
                parent.wait(timeout=3)
                deadline = time.monotonic() + 3
                status = None
                while time.monotonic() < deadline:
                    pid, result = os.waitpid(child, os.WNOHANG)
                    if pid == child:
                        status = result
                        break
                    time.sleep(0.02)
                self.assertIsNotNone(status, 'Native child survived parent death')
                self.assertTrue(os.WIFSIGNALED(status))
                self.assertEqual(signal.SIGKILL, os.WTERMSIG(status))
                child = None
            finally:
                if parent.poll() is None:
                    parent.kill()
                    parent.wait(timeout=3)
                if child:
                    try:
                        os.kill(child, signal.SIGKILL)
                        os.waitpid(child, 0)
                    except (ProcessLookupError, ChildProcessError):
                        pass
                if parent.stdout:
                    parent.stdout.close()

if __name__ == '__main__':
    unittest.main(verbosity=2)
