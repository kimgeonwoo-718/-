#!/bin/bash
# 실시간 교정을 폰과 같은 길(어절마다 스페이스 → correctTail)로 흉내 내 잰다.
#
#   tools/localcheck/live.sh                      live/*.tsv 전부
#   tools/localcheck/live.sh live/typos.tsv       파일 하나
#   LIVE_SHOW=20 tools/localcheck/live.sh          틀린 것을 몇 개까지 찍을지
#
# try.sh 는 전체교정(correctAll)이라 사용자가 치면서 보는 것과 다르다. 실시간은 이걸로 재라.
# Kiwi 는 여기 없다(안드로이드 AAR). 붙여 친 긴 덩어리는 실기기가 더 낫다.
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

if [ $# -gt 0 ]; then FILES=("$@"); else FILES=("$HERE"/live/*.tsv); fi

if [ ! -d "$WORK/live-main" ] || [ -n "$(find "$ROOT/core/src/main" -newer "$WORK/live-main" -type f 2>/dev/null | head -1)" ]; then
  rm -rf "$WORK/live-main"; mkdir -p "$WORK/live-main"
  kc -cp "$KS" -d "$WORK/live-main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
  cp -r "$ROOT/core/src/main/resources/." "$WORK/live-main/"
fi
rm -rf "$WORK/live-out"; mkdir -p "$WORK/live-out"
kc -cp "$KS:$WORK/live-main" -d "$WORK/live-out" "$HERE/Live.kt" || exit 1
mkdir -p "$WORK/live-tmp"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Djava.io.tmpdir="$WORK/live-tmp" -Xmx2g \
     -cp "$WORK/live-out:$WORK/live-main:$KS" LiveKt "${FILES[@]}"
