#!/bin/bash
# 사용: tools/llmcheck/build.sh <llama.cpp 소스 폴더(빌드가 끝난 것)>  — build/bin 의 libllama 에 붙여 cli 를 만든다.
set -eu
LLAMA=${1:?"llama.cpp 소스 폴더를 줘라"}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=${LLMCHECK_OUT:-${TMPDIR:-/tmp}/llmcheck}
mkdir -p "$OUT"
g++ -std=c++17 -O2 -I"$LLAMA/include" -I"$LLAMA/ggml/include" -I"$ROOT/keyboard/src/main/cpp" \
    "$ROOT/keyboard/src/main/cpp/spell_llm.cpp" "$HERE/cli.cpp" \
    -L"$LLAMA/build/bin" -lllama -lggml -lggml-base -lggml-cpu -Wl,-rpath,"$LLAMA/build/bin" \
    -lpthread -o "$OUT/cli"
echo "$OUT/cli"
