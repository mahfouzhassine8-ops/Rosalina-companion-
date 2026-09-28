#!/usr/bin/env bash
set -euo pipefail
MODE="${1:?Select cpu or vulkan}"
case "$MODE" in cpu|vulkan) ;; *) echo 'Invalid backend' >&2; exit 2;; esac
sdkmanager --install 'ndk;29.0.13113456' 'cmake;3.31.6'
sudo apt-get update -qq
sudo apt-get install -y ninja-build glslc
NDK="$ANDROID_HOME/ndk/29.0.13113456"
ARGS=()
SUFFIX=''
VK=OFF
if [[ "$MODE" == vulkan ]]; then
  VK=ON; SUFFIX=-vulkan
  cmake -S third_party/SPIRV-Headers -B spirv-headers-build -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DCMAKE_INSTALL_PREFIX="$PWD/vulkan-deps"
  cmake --install spirv-headers-build
  SPIRV_CONFIG="$(find "$PWD/vulkan-deps" -name SPIRV-HeadersConfig.cmake -print -quit)"
  test -n "$SPIRV_CONFIG"
  ARGS+=("-DSPIRV-Headers_DIR=$(dirname "$SPIRV_CONFIG")")
  ARGS+=("-DVulkan_INCLUDE_DIR=$PWD/third_party/Vulkan-Headers/include")
  ARGS+=("-DVulkan_LIBRARY=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/30/libvulkan.so")
  ARGS+=("-DVulkan_GLSLC_EXECUTABLE=/usr/bin/glslc")
fi
cmake -S unified-native -B "native-$MODE" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release -DSD_SOURCE="$PWD/third_party/stable-diffusion.cpp" \
  -DROSALINA_VULKAN="$VK" "${ARGS[@]}"
cmake --build "native-$MODE" --target rosalina-image rosalina-motion --parallel 2
mkdir -p native-output qa
for ENGINE in image motion; do
  OUT="native-output/librosalina-$ENGINE$SUFFIX.so"
  cp "native-$MODE/rosalina-$ENGINE" "$OUT"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$OUT"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d "$OUT" | tee -a qa/worker-dependencies.txt
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" --dyn-syms "$OUT" | grep -q prctl
  chmod 755 "$OUT"
done
if grep -E 'NEEDED.*(ggml|stable-diffusion|c\+\+_shared|omp)' qa/worker-dependencies.txt; then
  echo 'An isolated worker unexpectedly depends on a colliding shared engine' >&2
  exit 1
fi
sha256sum native-output/*.so > qa/native-checksums.txt
