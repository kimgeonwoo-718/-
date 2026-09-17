#!/bin/bash
# 교정 엔진에 글을 넣어 보고 결과를 찍는다. 사전과 언어모델까지 올린 **진짜 엔진**이다.
#
# 왜 필요한가: 단위 시험(test-core.sh)은 규칙만 켜 놓고 돌아서, "실기기에서 이 말이
# 안 고쳐진다" 는 제보를 그대로 재현하지 못한다. 그때 쓰는 도구다.
#
#   tools/localcheck/try.sh 듣는둥마는둥 아버지가방에들어가신다
#   tools/localcheck/try.sh -f 문장들.txt
#
# Kiwi 는 안 올라간다(안드로이드용 AAR 이라). 공백을 아예 안 친 여덟 음절 넘는 덩어리는
# 실기기보다 결과가 나쁘게 나온다 — 그 차이는 docs/HANDOFF.md 에 적혀 있다.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${LOCALCHECK_WORK:-${TMPDIR:-/tmp}/spell-localcheck}
mkdir -p "$WORK"

KS=$(find /root/.gradle -name "kotlin-stdlib-2.0.21.jar" | head -1)
CO=$(find /root/.gradle -name "kotlinx-coroutines-core-jvm-*.jar" | head -1)
CP=$(find /root/.gradle/caches -name "kotlin-compiler-embeddable-2.0.21.jar" | head -1)
TR=$(find /root/.gradle -name "trove4j-*.jar" -o -name "annotations-13*.jar" | tr '\n' ':')
for need in "$KS" "$CP"; do
  [ -n "$need" ] || { echo "그레이들 캐시에 필요한 jar 이 없다. 한 번은 CI 가 받아 줘야 한다." >&2; exit 2; }
done

kc() { java -cp "$CP:$KS:$CO:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib "$@"; }

# 넣을 글을 파일로 모은다. 명령줄 인자는 JVM 이 UTF-8 로 안 읽어 줄 때가 있다.
IN="$WORK/try-input.txt"
if [ "${1:-}" = "-f" ]; then
  [ -f "${2:-}" ] || { echo "파일이 없다: ${2:-}" >&2; exit 2; }
  cp "$2" "$IN"
else
  [ $# -gt 0 ] || { echo "쓸 글을 달라.  보기: $0 듣는둥마는둥" >&2; exit 2; }
  printf '%s\n' "$@" > "$IN"
fi

rm -rf "$WORK/try-main" "$WORK/try-out"; mkdir -p "$WORK/try-main" "$WORK/try-out"
kc -cp "$KS" -d "$WORK/try-main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
cp -r "$ROOT/core/src/main/resources/." "$WORK/try-main/"

cat > "$WORK/Try.kt" <<'KOTLIN'
import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

fun main(args: Array<String>) {
    val cache = File(System.getProperty("java.io.tmpdir"), "spell-try")
    val engine = CorrectionEngine()
    val spacer = runCatching { Spacer(SpacingDictionary.open(cache)) }
        .onSuccess { engine.spacer = it; engine.speller = Speller(it) }
        .getOrNull()
    val lm = runCatching { LanguageModel.open(cache) }
        .onSuccess { engine.context = ContextCorrector(it, spacer) }
        .getOrNull()
    System.err.println("사전=" + (spacer != null) + "  언어모델=" + (lm != null) + "  (Kiwi 는 없다)")

    File(args[0]).readLines().filter { it.isNotBlank() }.forEach { line ->
        val r = engine.correctAll(line)
        println("입력: " + line)
        println("결과: " + r.text + (if (r.text == line) "   (그대로)" else ""))
        if (lm != null) {
            // 언어모델이 각 낱말을 아는지. 왜 거기서 멈췄는지 단서가 된다 —
            // 붙여 쓴 꼴을 낱말로 알고 있으면 디코더는 더 가르지 않는다.
            println("낱말: " + r.text.split(" ").filter { it.isNotBlank() }
                .joinToString("  ") { w -> w + "=" + (if (lm.lnCount(w) != null) "앎" else "모름") })
        }
        println()
    }
}
KOTLIN
kc -cp "$KS:$WORK/try-main" -d "$WORK/try-out" "$WORK/Try.kt" || exit 1

# 사전을 푸는 임시 폴더를 따로 준다. 기본 임시 폴더에 쌓이면 디스크가 찬다.
rm -rf "$WORK/try-tmp"; mkdir -p "$WORK/try-tmp"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Djava.io.tmpdir="$WORK/try-tmp" \
     -cp "$WORK/try-out:$WORK/try-main:$KS" TryKt "$IN"
