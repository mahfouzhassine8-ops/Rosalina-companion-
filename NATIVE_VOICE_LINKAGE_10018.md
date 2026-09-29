# 10018 native voice linkage correction

This note supersedes the initial Java-bridge-only dependency statement in
COMPLETION_10018.md. No phone acceptance is claimed.

Integrated run 36555539862 compiled both APK variants and passed 25 of 26 Android
instrumented tests. The actual Chatterbox test failed before synthesis because
libonnxruntime4j_jni.so could not resolve OrtGetApiBase@VERS_1.23.2 against the
protected sherpa runtime. Compilation alone did not expose this linkage failure.

The repair keeps sherpa's libonnxruntime.so byte-identical. It packages the pinned
official ONNX Runtime 1.23.2 C library as libvoiceort.so and redirects only the new
voice JNI dependency to that unique SONAME. Version-need metadata uses the same
retargeted dynamic string. In-place string edits preserve file size and ELF LOAD
layout, with 16-KiB alignment asserted. No model/operator code is changed.

The upstream AAR is pinned to SHA-256
82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827.
Original and packaged candidate library hashes are recorded per ABI. Release
verification separately checks the protected C library inside the actual APK.

All 41 canonical application/test checkpoint files remain unchanged. The existing
host-tested Chatterbox phone pack is reused, not regenerated. Android model
playback, interruption, restart and UI tests must be rerun against the corrected
native package. Evidence capture now retains the first successful archive through
the subsequent emulator-only update test.
