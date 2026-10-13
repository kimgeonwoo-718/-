#!/bin/bash
# 규칙마다 따로 돌려 **멀쩡한 글에서** 무엇을 바꾸는지 찍는다(RuleAudit.kt).
#
#   tools/localcheck/rule-audit.sh clean-news:/tmp/news.txt clean-wiki:/tmp/wiki.txt chat:/tmp/chat.txt > /tmp/audit.txt
#
# 이름이 clean 으로 시작하는 파일은 멀쩡한 글(기사·위키·격식체)로, 나머지는 사람이 친 글로 센다.
# 멀쩡한 글에서 걸린 수가 큰 규칙부터 의심하라. 말뭉치는 저장소에 넣지 않는다(fetch_corpus.sh·build-sheet.py).
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

[ $# -gt 0 ] || { echo "말뭉치를 달라.  보기: $0 clean-news:/tmp/news.txt chat:/tmp/chat.txt" >&2; exit 2; }

if [ ! -d "$WORK/audit-main" ] || [ -n "$(find "$ROOT/core/src/main" -newer "$WORK/audit-main" -type f 2>/dev/null | head -1)" ]; then
  rm -rf "$WORK/audit-main"; mkdir -p "$WORK/audit-main"
  kc -cp "$KS" -d "$WORK/audit-main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
  cp -r "$ROOT/core/src/main/resources/." "$WORK/audit-main/"
fi
rm -rf "$WORK/audit-out"; mkdir -p "$WORK/audit-out"
kc -cp "$KS:$WORK/audit-main" -d "$WORK/audit-out" "$HERE/RuleAudit.kt" || exit 1
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Xmx4g -cp "$WORK/audit-out:$WORK/audit-main:$KS" RuleAuditKt "$@"
