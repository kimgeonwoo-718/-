#!/bin/bash
# 오타 되살리기를 잰다: 맞는 문장에 흔한 오타를 하나 넣고 실시간 교정으로 되돌아오는지.
#
#   tools/localcheck/typo.sh                                   구어체(chat.txt)
#   SPELL_EVAL_FILES=a.tsv,b.tsv tools/localcheck/typo.sh      격식체(KorNLI)도
#   TYPO_SHOW=30 ...                                           못 고친 것을 찍는다
#
# 디코더의 편집을 아끼면 멀쩡한 말을 덜 바꾸지만 진짜 오타도 덜 고친다. 그 값을 보려고 만들었다.
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

FILES=("$HERE/spacing/chat.txt")

if [ ! -d "$WORK/live-main" ] || [ -n "$(find "$ROOT/core/src/main" -newer "$WORK/live-main" -type f 2>/dev/null | head -1)" ]; then
  rm -rf "$WORK/live-main"; mkdir -p "$WORK/live-main"
  kc -cp "$KS" -d "$WORK/live-main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
  cp -r "$ROOT/core/src/main/resources/." "$WORK/live-main/"
fi
rm -rf "$WORK/typo-out"; mkdir -p "$WORK/typo-out"
kc -cp "$KS:$WORK/live-main" -d "$WORK/typo-out" "$HERE/Typo.kt" || exit 1
mkdir -p "$WORK/live-tmp"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Djava.io.tmpdir="$WORK/live-tmp" -Xmx2g \
     -cp "$WORK/typo-out:$WORK/live-main:$KS" TypoKt "${FILES[@]}"
