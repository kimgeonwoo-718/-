#!/bin/bash
# 폰의 Kiwi 오타 교정 단계(KiwiSpacer.fix)를 컴퓨터에서 돌려 본다. **폰에서만 이상한 제보**를 재현하는 도구다.
#
#   tools/localcheck/kiwi-fix.sh "내 이름은 류서인 님" "류진 한테 말했어"
#   tools/localcheck/kiwi-fix.sh --no-guard "류서인 형"      TypoGuard 를 건너뛴 고치기 전 모습
#
# 왜 필요한가: try.sh·live.sh 에는 Kiwi 가 없다(안드로이드 AAR 이라). 그래서 Kiwi 오타 단계가 바꾸는 것은 거기서 안 보였다.
# '류서인 → 유서인'(2026-10-09)은 이 단계가 한 일이다. kiwipiepy 를 같은 버전으로 깔아 같은 엔진을 돌린다.
# 처음 한 번은 kiwipiepy(모델 포함 ~80MB)를 받는다. 이 컨테이너에서는 pypi.org 는 열려 있다.
#
# 알아 둘 것: 폰 파이프라인의 맨 끝 단계만 흉내 낸다. 코어(규칙·디코더)가 먼저 바꾼 글은 넘기지 않는다 —
# 코어까지 같이 보려면 이 결과를 live.sh 의 결과와 견줘라.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${LOCALCHECK_WORK:-${TMPDIR:-/tmp}/spell-localcheck}
mkdir -p "$WORK"

VER=$(grep -o 'kiwiVersion = "v[^"]*"' "$ROOT/keyboard/build.gradle.kts" | sed 's/.*"v\(.*\)"/\1/')
[ -n "$VER" ] || { echo "build.gradle.kts 에서 kiwiVersion 을 못 읽었다." >&2; exit 2; }

VENV="$WORK/kiwi-venv"
if [ ! -x "$VENV/bin/python" ] || ! "$VENV/bin/python" -c "import importlib.metadata as m,sys; sys.exit(0 if m.version('kiwipiepy')=='$VER' else 1)" 2>/dev/null; then
  echo "kiwipiepy $VER 을 설치한다(처음 한 번)…" >&2
  python3 -m venv "$VENV" || exit 2
  "$VENV/bin/pip" install --quiet "kiwipiepy==$VER" || { echo "kiwipiepy $VER 을 못 받았다." >&2; exit 2; }
fi

KS=$(find /root/.gradle -name "kotlin-stdlib-2.0.21.jar" | head -1)
CO=$(find /root/.gradle -name "kotlinx-coroutines-core-jvm-*.jar" | head -1)
CP=$(find /root/.gradle/caches -name "kotlin-compiler-embeddable-2.0.21.jar" | head -1)
TR=$(find /root/.gradle -name "trove4j-*.jar" -o -name "annotations-13*.jar" | tr '\n' ':')
[ -n "$KS" ] && [ -n "$CP" ] || { echo "그레이들 캐시에 필요한 jar 이 없다." >&2; exit 2; }
kc() { java -cp "$CP:$KS:$CO:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib "$@"; }

# 코어와 도우미는 코어 소스가 바뀌었을 때만 다시 컴파일한다.
OUT="$WORK/kiwi-sim"
if [ ! -d "$OUT" ] || [ -n "$(find "$ROOT/core/src/main" "$HERE/kiwi" -newer "$OUT" -type f 2>/dev/null | head -1)" ]; then
  rm -rf "$OUT"; mkdir -p "$OUT"
  kc -cp "$KS" -d "$OUT" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") "$HERE/kiwi/KiwiHelper.kt" 2>&1 | grep -v "^Picked up" | grep -v "^$" | grep -E "error:" && exit 1
  cp -r "$ROOT/core/src/main/resources/." "$OUT/"
fi
mkdir -p "$WORK/kiwi-lm"

export KIWI_SIM_CP="$OUT:$KS"
export KIWI_SIM_LM="$WORK/kiwi-lm"
exec "$VENV/bin/python" -W ignore "$HERE/kiwi/sim.py" "$@"
