#!/bin/bash
# 띄어쓰기 고침이 이득인지 손해인지 잰다. spacing/README.md 참고.
#
#   tools/localcheck/measure-spacing.sh            지금 코드로 잰다
#   tools/localcheck/measure-spacing.sh --diff     무엇을 건드렸는지도 보여준다
#
# 쓰는 법: 고치기 **전에** 한 번 돌려 숫자를 적어 두고, 고친 **뒤에** 다시 돌려 견준다.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${LOCALCHECK_WORK:-${TMPDIR:-/tmp}/spell-localcheck}
DIFF=${1:-}
mkdir -p "$WORK"

KS=$(find /root/.gradle -name "kotlin-stdlib-2.0.21.jar" | head -1)
CO=$(find /root/.gradle -name "kotlinx-coroutines-core-jvm-*.jar" | head -1)
CP=$(find /root/.gradle/caches -name "kotlin-compiler-embeddable-2.0.21.jar" | head -1)
TR=$(find /root/.gradle -name "trove4j-*.jar" -o -name "annotations-13*.jar" | tr '\n' ':')
[ -n "$KS" ] && [ -n "$CP" ] || { echo "그레이들 캐시에 필요한 jar 이 없다." >&2; exit 2; }
kc() { java -cp "$CP:$KS:$CO:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib "$@"; }

rm -rf "$WORK/ms-main" "$WORK/ms-out"; mkdir -p "$WORK/ms-main" "$WORK/ms-out"
kc -cp "$KS" -d "$WORK/ms-main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
cp -r "$ROOT/core/src/main/resources/." "$WORK/ms-main/"

cat > "$WORK/Measure.kt" <<'KOTLIN'
import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.SpacingDictionary
import com.spellkeyboard.core.spacing.Speller
import java.io.File

fun main(args: Array<String>) {
    val cache = File(System.getProperty("java.io.tmpdir"), "spell-measure")
    val engine = CorrectionEngine()
    val spacer = Spacer(SpacingDictionary.open(cache))
    engine.spacer = spacer
    engine.speller = Speller(spacer)
    engine.context = ContextCorrector(LanguageModel.open(cache), spacer)

    val showDiff = args.size > 2 && args[2] == "--diff"
    for ((path, keep) in listOf(args[0] to true, args[1] to false)) {
        val lines = File(path).readLines().filter { it.isNotBlank() }
        var touched = 0
        val notes = StringBuilder()
        for (line in lines) {
            val out = engine.correctAll(line).text
            if (out != line) {
                touched++
                if (showDiff) notes.append("    ").append(line).append("\n     → ").append(out).append("\n")
            }
        }
        val label = if (keep) "건드리면 안 되는 문장" else "갈라야 하는 문장"
        val good = if (keep) "적을수록 좋다" else "많을수록 좋다"
        println("%-22s %2d / %-3d 손댐   (%s)".format(label, touched, lines.size, good))
        if (showDiff && notes.isNotEmpty()) print(notes)
    }
}
KOTLIN
kc -cp "$KS:$WORK/ms-main" -d "$WORK/ms-out" "$WORK/Measure.kt" || exit 1

rm -rf "$WORK/ms-tmp"; mkdir -p "$WORK/ms-tmp"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Djava.io.tmpdir="$WORK/ms-tmp" \
     -cp "$WORK/ms-out:$WORK/ms-main:$KS" MeasureKt \
     "$ROOT/tools/localcheck/spacing/keep.txt" "$ROOT/tools/localcheck/spacing/split.txt" "$DIFF"
