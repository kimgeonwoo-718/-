#!/bin/bash
# 문장을 잔뜩 넣고 엔진이 무엇을 건드렸는지 종류별로 센다(Mine.kt). 파일마다 JVM 하나씩 나란히 돈다.
#
#   tools/localcheck/build-sheet.py /tmp/corpus /tmp/sheet        시험지 만들기(말뭉치는 fetch_corpus.sh 로)
#   tools/localcheck/mine.sh /tmp/sheet/*.txt                     실시간 길
#   MINE_MODE=full tools/localcheck/mine.sh /tmp/sheet/*.txt      전체교정 길
#   MINE_OUT=/tmp/mine tools/localcheck/mine.sh …                 보고서·바뀐 문장 tsv 를 남길 곳(기본: 작업 폴더/mine)
#
# Kiwi 는 여기 없다(안드로이드 AAR). 폰에서만 도는 Kiwi 오타 단계는 kiwi-fix.sh 로 따로 본다.
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

[ $# -gt 0 ] || { echo "시험지를 달라.  보기: $0 /tmp/sheet/*.txt" >&2; exit 2; }

if [ ! -d "$WORK/mine-main" ] || [ -n "$(find "$ROOT/core/src/main" -newer "$WORK/mine-main" -type f 2>/dev/null | head -1)" ]; then
  rm -rf "$WORK/mine-main"; mkdir -p "$WORK/mine-main"
  kc -cp "$KS" -d "$WORK/mine-main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
  cp -r "$ROOT/core/src/main/resources/." "$WORK/mine-main/"
fi
rm -rf "$WORK/mine-out"; mkdir -p "$WORK/mine-out"
kc -cp "$KS:$WORK/mine-main" -d "$WORK/mine-out" "$HERE/Mine.kt" || exit 1
mkdir -p "$WORK/live-tmp"

OUT=${MINE_OUT:-$WORK/mine}
MODE=${MINE_MODE:-live}
mkdir -p "$OUT"
JOBS=${MINE_JOBS:-$(nproc)}
# 사전·언어모델을 **먼저 한 번** 풀어 둔다. 처음 도는 작업 폴더에서 JVM 여럿이 동시에 풀면 같은 임시 파일에 겹쳐 써서
# 앞서 뜬 몇 개가 덜 풀린 모델을 읽는다 — 그렇게 잰 값은 엉터리였다(2026-10-11, 같은 코드인데 시험지 하나가 691 → 1402 문장 바뀜).
java -Dfile.encoding=UTF-8 -Djava.io.tmpdir="$WORK/live-tmp" -Xmx2g -cp "$WORK/mine-out:$WORK/mine-main:$KS" MineKt /dev/null > /dev/null 2>&1
running=0
for f in "$@"; do
  name=$(basename "$f" .txt)
  MINE_TSV="$OUT/$name.$MODE.tsv" java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Djava.io.tmpdir="$WORK/live-tmp" -Xmx2g \
       -cp "$WORK/mine-out:$WORK/mine-main:$KS" MineKt "$f" > "$OUT/$name.$MODE.txt" 2>&1 &
  running=$((running + 1))
  if [ "$running" -ge "$JOBS" ]; then wait -n; running=$((running - 1)); fi
done
wait
for f in "$@"; do
  name=$(basename "$f" .txt)
  grep -v 'JAVA_TOOL_OPTIONS' "$OUT/$name.$MODE.txt" | sed -n '1,3p'
done
echo "보고서: $OUT/*.$MODE.txt   바뀐 문장: $OUT/*.$MODE.tsv"
