#!/bin/bash
# 띄어쓰기 품질을 **여러 잣대로 한꺼번에** 잰다. measure-spacing.sh 가 회귀를 잡는
# 그물이라면 이쪽은 자다 — 숫자가 얼마나 좋은지를 본다.
#
#   tools/localcheck/eval-spacing.sh                  붙어 있는 시험지(chat.txt)로
#   SPELL_EVAL_FILES=a.tsv,b.tsv tools/localcheck/eval-spacing.sh
#   EVAL_LIMIT=800 tools/localcheck/eval-spacing.sh   빨리 보고 싶을 때
#   EVAL_SHOW=40 ...                                  틀린 것을 찍는다
#
# 네 가지를 잰다.
#   1. 멀쩡한 글을 건드림      — 낮을수록 좋다. 제일 중요하다.
#   2. 공백 하나 지운 것 복원  — 실시간 교정이 실제로 만나는 입력.
#   3. 공백 절반 지운 것       — 급히 친 글. 경계 정밀도/재현율/F1.
#   4. 공백 전부 지운 것       — 전체교정이 만나는 최악.
#
# Kiwi 는 여기 없다(안드로이드 AAR). 3·4번은 실기기보다 나쁘게 나온다.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${LOCALCHECK_WORK:-${TMPDIR:-/tmp}/spell-localcheck}
mkdir -p "$WORK"

KS=$(find /root/.gradle -name "kotlin-stdlib-2.0.21.jar" | head -1)
CO=$(find /root/.gradle -name "kotlinx-coroutines-core-jvm-*.jar" | head -1)
CP=$(find /root/.gradle/caches -name "kotlin-compiler-embeddable-2.0.21.jar" | head -1)
TR=$(find /root/.gradle -name "trove4j-*.jar" -o -name "annotations-13*.jar" | tr '\n' ':')
[ -n "$KS" ] && [ -n "$CP" ] || { echo "그레이들 캐시에 필요한 jar 이 없다." >&2; exit 2; }
kc() { java -cp "$CP:$KS:$CO:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib "$@"; }

rm -rf "$WORK/ev-main" "$WORK/ev-out"; mkdir -p "$WORK/ev-main" "$WORK/ev-out"
kc -cp "$KS" -d "$WORK/ev-main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
cp -r "$ROOT/core/src/main/resources/." "$WORK/ev-main/"

cp "$ROOT/tools/localcheck/Eval.kt" "$WORK/Eval.kt"
kc -cp "$KS:$WORK/ev-main" -d "$WORK/ev-out" "$WORK/Eval.kt" || exit 1

rm -rf "$WORK/ev-tmp"; mkdir -p "$WORK/ev-tmp"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Djava.io.tmpdir="$WORK/ev-tmp" -Xmx2g \
     -cp "$WORK/ev-out:$WORK/ev-main:$KS" EvalKt "$ROOT/tools/localcheck/spacing/chat.txt"
