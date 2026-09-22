#!/bin/bash
# 키보드 모듈 코틀린 타입 검사. 안드로이드 SDK 없이 돈다. 왜 필요한지는 README.md 참고.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${LOCALCHECK_WORK:-${TMPDIR:-/tmp}/spell-localcheck}
mkdir -p "$WORK"

KS=$(find /root/.gradle -name "kotlin-stdlib-2.0.21.jar" | head -1)
CO=$(find /root/.gradle -name "kotlinx-coroutines-core-jvm-*.jar" | head -1)
CP=$(find /root/.gradle/caches -name "kotlin-compiler-embeddable-2.0.21.jar" | head -1)
TR=$(find /root/.gradle -name "trove4j-*.jar" -o -name "annotations-13*.jar" | tr '\n' ':')

# android.jar 대신 메이븐 중앙의 robolectric android-all. 구글 메이븐은 막혀 있다.
ANDROID="$WORK/android-all.jar"
if [ ! -s "$ANDROID" ]; then
  echo "android-all 을 받는다 (한 번, 137MB)…"
  curl -fsSL -o "$ANDROID" \
    "https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar" \
    || { echo "못 받았다. 망 정책을 확인해라." >&2; exit 2; }
fi

# 코어를 먼저 컴파일한다. 키보드가 이걸 쓴다.
rm -rf "$WORK/main"; mkdir -p "$WORK/main"
java -cp "$CP:$KS:$CO:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib \
     -cp "$KS" -d "$WORK/main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1

# Kiwi AAR 안의 classes.jar. 빌드가 받아 둔 것이 있으면 쓰고, 없으면 직접 받는다.
# 판 번호는 build.gradle.kts 에서 읽는다 — 거기서 올리면 여기도 따라온다.
KIWI=$(find "$ROOT/keyboard/build" -name "kiwi-android-*.aar" 2>/dev/null | head -1)
if [ -z "$KIWI" ]; then
  VER=$(grep -o 'kiwiVersion = "[^"]*"' "$ROOT/keyboard/build.gradle.kts" | cut -d'"' -f2)
  KIWI="$WORK/kiwi-android-$VER.aar"
  if [ ! -s "$KIWI" ]; then
    echo "Kiwi AAR 을 받는다 ($VER, 한 번)…"
    curl -fsSL -o "$KIWI" \
      "https://github.com/bab2min/Kiwi/releases/download/$VER/kiwi-android-$VER.aar" \
      || { echo "Kiwi AAR 을 못 받았다." >&2; exit 2; }
  fi
fi
rm -rf "$WORK/kiwi"; mkdir -p "$WORK/kiwi"
unzip -o -q "$KIWI" classes.jar -d "$WORK/kiwi" || { echo "AAR 에서 classes.jar 을 못 꺼냈다." >&2; exit 2; }
KIWIJAR=":$WORK/kiwi/classes.jar"

# R 과 BuildConfig 는 소스가 쓰는 이름만 모아 다시 만든다.
grep -rho "R\.[a-z_]*\.[A-Za-z0-9_]*" "$ROOT/keyboard/src/main/java/" | sort -u > "$WORK/rrefs.txt"
python3 - "$WORK" <<'PY'
import collections, sys
work = sys.argv[1]
g = collections.defaultdict(set)
for line in open(work + '/rrefs.txt'):
    line = line.strip()
    if line:
        _, kind, name = line.split('.', 2)
        g[kind].add(name)
out = ['package com.spellkeyboard.ko', '', '// 자동 생성 — 안드로이드 빌드가 만드는 R 을 흉내낸다.', 'object R {']
for kind in sorted(g):
    out.append(f'    object {kind} {{')
    out += [f'        const val {n} = 0' for n in sorted(g[kind])]
    out.append('    }')
out += ['}', '', 'object BuildConfig {', '    const val AI_SERVER_URL = ""', '    const val GOOGLE_CLIENT_ID = ""', '}']
open(work + '/stub_R.kt', 'w', encoding='utf-8').write('\n'.join(out) + '\n')
PY

rm -rf "$WORK/kb"; mkdir -p "$WORK/kb"
java -cp "$CP:$KS:$CO:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib \
  -cp "$KS:$ANDROID:$WORK/main$KIWIJAR" -d "$WORK/kb" \
  $(find "$ROOT/keyboard/src/main/java" -name "*.kt") \
  $(find "$HERE/stubs" -name "*.kt") "$WORK/stub_R.kt" > "$WORK/kb.log" 2>&1
grep -v "^Picked up" "$WORK/kb.log" || true
# **컴파일러는 오류가 나도 0 을 줄 때가 있다.** 나온 글에서 직접 본다.
if grep -q "error:" "$WORK/kb.log"; then echo "키보드 모듈 타입 검사 실패"; exit 1; fi
echo "키보드 모듈 타입 검사 통과"
