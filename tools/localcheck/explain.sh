#!/bin/bash
# 디코더가 왜 그렇게 골랐는지 본다.  explain.sh "오늘날씨가 정말 좋다"
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${LOCALCHECK_WORK:-${TMPDIR:-/tmp}/spell-localcheck}
mkdir -p "$WORK"
KS=$(find /root/.gradle -name "kotlin-stdlib-2.0.21.jar" | head -1)
CO=$(find /root/.gradle -name "kotlinx-coroutines-core-jvm-*.jar" | head -1)
CP=$(find /root/.gradle/caches -name "kotlin-compiler-embeddable-2.0.21.jar" | head -1)
TR=$(find /root/.gradle -name "trove4j-*.jar" -o -name "annotations-13*.jar" | tr '\n' ':')
kc() { java -cp "$CP:$KS:$CO:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib "$@"; }

IN="$WORK/explain-input.txt"
if [ "${1:-}" = "-f" ]; then cp "$2" "$IN"; else printf '%s\n' "$@" > "$IN"; fi

if [ ! -d "$WORK/ex-main" ] || [ -n "$(find "$ROOT/core/src/main/kotlin" -newer "$WORK/ex-main" -name '*.kt' 2>/dev/null)" ]; then
  rm -rf "$WORK/ex-main"; mkdir -p "$WORK/ex-main"
  kc -cp "$KS" -d "$WORK/ex-main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
  cp -r "$ROOT/core/src/main/resources/." "$WORK/ex-main/"
fi
rm -rf "$WORK/ex-out"; mkdir -p "$WORK/ex-out"
kc -cp "$KS:$WORK/ex-main" -d "$WORK/ex-out" "$ROOT/tools/localcheck/Explain.kt" || exit 1
rm -rf "$WORK/ex-tmp"; mkdir -p "$WORK/ex-tmp"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Djava.io.tmpdir="$WORK/ex-tmp" \
     -cp "$WORK/ex-out:$WORK/ex-main:$KS" ExplainKt "$IN"
