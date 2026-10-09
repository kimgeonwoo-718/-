#!/bin/bash
# 헷갈리는 말(낫/낳)을 규칙표만(R)·학습 모델만(M)·둘 다(RM) 로 재서 견준다. Confusable.kt 참고.
#
#   tools/localcheck/confusable.sh                              시험지 전부(tools/confusable/eval/*.tsv)
#   LIVE_SHOW=20 CONF_SHOW=RM tools/localcheck/confusable.sh     틀린 것을 찍는다(설정 R/M/RM 중 하나)
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${LOCALCHECK_WORK:-${TMPDIR:-/tmp}/spell-localcheck}
mkdir -p "$WORK"
KS=$(find /root/.gradle -name "kotlin-stdlib-2.0.21.jar" | head -1)
CO=$(find /root/.gradle -name "kotlinx-coroutines-core-jvm-*.jar" | head -1)
CP=$(find /root/.gradle/caches -name "kotlin-compiler-embeddable-2.0.21.jar" | head -1)
TR=$(find /root/.gradle -name "trove4j-*.jar" -o -name "annotations-13*.jar" | tr '\n' ':')
kc() { java -cp "$CP:$KS:$CO:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib "$@"; }

if [ $# -gt 0 ]; then FILES=("$@"); else FILES=("$ROOT"/tools/confusable/eval/*.tsv); fi

if [ ! -d "$WORK/live-main" ] || [ -n "$(find "$ROOT/core/src/main" -newer "$WORK/live-main" -type f 2>/dev/null | head -1)" ]; then
  rm -rf "$WORK/live-main"; mkdir -p "$WORK/live-main"
  kc -cp "$KS" -d "$WORK/live-main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
  cp -r "$ROOT/core/src/main/resources/." "$WORK/live-main/"
fi
rm -rf "$WORK/live-out"; mkdir -p "$WORK/live-out"
kc -cp "$KS:$WORK/live-main" -d "$WORK/live-out" "$HERE/Confusable.kt" || exit 1
mkdir -p "$WORK/live-tmp"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Djava.io.tmpdir="$WORK/live-tmp" -Xmx2g \
     -cp "$WORK/live-out:$WORK/live-main:$KS" ConfusableKt "${FILES[@]}"
