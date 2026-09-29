#!/usr/bin/env bash
set -euo pipefail
mkdir -p unified/libs unified/src/main/assets/licenses qa
curl --fail --location --retry 3 -o unified/libs/sherpa-onnx-1.13.8.aar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
echo '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96  unified/libs/sherpa-onnx-1.13.8.aar' | sha256sum -c
cp tools/voice-pins.json unified/src/main/assets/voice-model-checksums.json
git rev-parse HEAD > unified/src/main/assets/source-commit.txt
if [ "${1:-}" = release ]; then
 curl --fail --location --retry 3 -o unified/src/main/assets/taew2_2.safetensors https://raw.githubusercontent.com/madebyollin/taehv/011dfc2112197741c540e0bdd5b7b67bcc930771/safetensors/taew2_2.safetensors
 echo 'b84609b2a133d48434bd9636bfcb44bf05168dc436e2d3cecf26256faa1f5325  unified/src/main/assets/taew2_2.safetensors' | sha256sum -c
 cp third_party/llama.cpp/LICENSE unified/src/main/assets/licenses/llama-LICENSE
fi
curl --fail --location --retry 3 -o unified/src/main/assets/licenses/sherpa-LICENSE https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/LICENSE
curl --fail --location --retry 3 -o unified/src/main/assets/licenses/stable-diffusion-LICENSE https://raw.githubusercontent.com/leejet/stable-diffusion.cpp/3f8527a46c54ecf4cb4ed6003da8e8982283c73c/LICENSE
curl --fail --location --retry 3 -o unified/src/main/assets/licenses/taehv-LICENSE https://raw.githubusercontent.com/madebyollin/taehv/011dfc2112197741c540e0bdd5b7b67bcc930771/LICENSE
