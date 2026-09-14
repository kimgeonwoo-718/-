#!/bin/bash
# 코어 단위 시험을 그레이들 없이 돌린다. 왜 필요한지는 README.md 참고.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${LOCALCHECK_WORK:-${TMPDIR:-/tmp}/spell-localcheck}
mkdir -p "$WORK"

KS=$(find /root/.gradle -name "kotlin-stdlib-2.0.21.jar" | head -1)
KT=$(find /root/.gradle/caches -name "kotlin-test-2.0.21.jar" | head -1)
CO=$(find /root/.gradle -name "kotlinx-coroutines-core-jvm-*.jar" | head -1)
CP=$(find /root/.gradle/caches -name "kotlin-compiler-embeddable-2.0.21.jar" | head -1)
TR=$(find /root/.gradle -name "trove4j-*.jar" -o -name "annotations-13*.jar" | tr '\n' ':')
J=$(find /root/.gradle/caches \( -name "junit-jupiter-api-*.jar" -o -name "junit-jupiter-engine-*.jar" \
     -o -name "junit-platform-launcher-*.jar" -o -name "junit-platform-commons-*.jar" \
     -o -name "junit-platform-engine-*.jar" -o -name "kotlin-test-junit5-*.jar" \
     -o -name "apiguardian-api-*.jar" -o -name "opentest4j-*.jar" \) | tr '\n' ':')
for need in "$KS" "$KT" "$CP" "$J"; do
  [ -n "$need" ] || { echo "그레이들 캐시에 필요한 jar 이 없다. 한 번은 CI 가 받아 줘야 한다." >&2; exit 2; }
done

kc() { java -cp "$CP:$KS:$CO:$TR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -no-stdlib "$@"; }

rm -rf "$WORK/main" "$WORK/test"; mkdir -p "$WORK/main" "$WORK/test"
kc -cp "$KS" -d "$WORK/main" $(find "$ROOT/core/src/main/kotlin" -name "*.kt") || exit 1
cp -r "$ROOT/core/src/main/resources/." "$WORK/main/"
# 시험이 internal 을 보려면 친구 경로가 필요하다. 그레이들이 조용히 해 주던 것이다.
kc -Xfriend-paths="$WORK/main" -cp "$KS:$KT:$J:$WORK/main" -d "$WORK/test" \
   $(find "$ROOT/core/src/test/kotlin" -name "*.kt") || exit 1

cat > "$WORK/RunTests.java" <<'JAVA'
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
public class RunTests {
    public static void main(String[] a) {
        LauncherDiscoveryRequest req = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectPackage("com.spellkeyboard.core")).build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(req, listener);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));
        listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out), 8);
        System.exit(listener.getSummary().getTotalFailureCount() > 0 ? 1 : 0);
    }
}
JAVA
javac -cp "$J" -d "$WORK" "$WORK/RunTests.java" || exit 1

# java.io.tmpdir 를 따로 준다. 시험이 여는 사전·모델이 임시 폴더에 풀리는데, 기본
# 임시 폴더에 쌓이면 디스크가 찬다.
rm -rf "$WORK/jtmp"; mkdir -p "$WORK/jtmp"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Djava.io.tmpdir="$WORK/jtmp" \
     -cp "$WORK:$WORK/test:$WORK/main:$KS:$KT:$J" RunTests
