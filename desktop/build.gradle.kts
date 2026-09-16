import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    application
}

// :core 는 버전 없이 같은 플러그인을 쓴다. 여기서 버전을 박아 두면 그쪽도 따라온다.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    // 전역 단축키(Ctrl+Alt+Space 로 입력창 부르기) 때문에 하나만 쓴다.
    // RegisterHotKey 를 부르려고 쓰는 것이지 키를 훔쳐보지 않는다 — 3-1 참고.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.spellkeyboard.desktop.MainKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }

    // 시험은 **화면 없이** 돈다. 이것이 없으면 쟁반·창 시험이 진짜 알림 영역을 건드리고,
    // 떠 있는 창 시험이 사용자 화면에 창을 띄운다. 일부러 첫 단언으로 못 박아 두었다.
    systemProperty("java.awt.headless", "true")
}

/**
 * 어디서나 `java -jar` 로 도는 한 덩어리 jar.
 *
 * shadow 플러그인을 쓰지 않는다. 바깥 의존성이 코틀린 표준 라이브러리 하나뿐이라
 * 직접 푸는 편이 빠르고, 플러그인 하나를 덜 받는다.
 */
val fatJar by tasks.registering(Jar::class) {
    archiveBaseName.set("SpellDesktop")
    archiveClassifier.set("")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    manifest {
        attributes["Main-Class"] = "com.spellkeyboard.desktop.MainKt"
        attributes["Implementation-Title"] = "맞춤법 교정기"
        // JNA 가 user32 를 불러 전역 단축키(RegisterHotKey)와 앞 창 확인
        // (GetForegroundWindow)을 한다. JDK 24 부터 네이티브 호출이 제한돼 지금은 실행할
        // 때마다 경고가 넉 줄 뜨고, **다음 판에서는 아예 막힌다** — 막히면 단축키와
        // 돌려 붙이기가 통째로 죽는다. 미리 열어 둔다.
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } })
    // 서명된 의존 jar 의 서명 파일이 섞이면 "Invalid signature file" 로 실행이 막힌다.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}

/**
 * 윈도우용 실행 파일. **윈도우에서 돌려야 한다** — jpackage 는 교차 빌드를 못 한다.
 *
 *     gradlew.bat -p desktop packageWindows
 *
 * `--type app-image` 라서 WiX 같은 것을 따로 깔 필요가 없다. 결과 폴더 안에
 * SpellDesktop.exe 가 있고, 자바 런타임이 그 안에 들어 있어 자바 없는 PC 에서도 돈다.
 */
val packageWindows by tasks.registering(Exec::class) {
    dependsOn(fatJar)
    group = "distribution"
    description = "윈도우 실행 파일(app-image)을 만든다. 윈도우에서만 된다."

    val distDir = layout.buildDirectory.dir("dist").get().asFile
    val outDir = layout.buildDirectory.dir("windows").get().asFile

    doFirst {
        outDir.deleteRecursively()
        outDir.mkdirs()
    }

    val jpackage = File(System.getProperty("java.home"), "bin/jpackage").let {
        if (it.exists()) it.absolutePath
        else File(System.getProperty("java.home"), "bin/jpackage.exe").absolutePath
    }
    commandLine(
        jpackage,
        "--type", "app-image",
        "--name", "SpellDesktop",
        "--app-version", "1.0",
        "--input", distDir.absolutePath,
        "--main-jar", "SpellDesktop.jar",
        "--main-class", "com.spellkeyboard.desktop.MainKt",
        "--dest", outDir.absolutePath,
        "--java-options", "-Xmx768m",
        "--java-options", "-Dfile.encoding=UTF-8",
        // fatJar 의 Enable-Native-Access 와 같은 까닭이다. .exe 는 매니페스트를 안 거치는
        // 길로도 뜨므로 여기에도 적어 둔다.
        "--java-options", "--enable-native-access=ALL-UNNAMED"
    )
    doLast {
        println("나왔다: ${outDir.absolutePath}")
    }
}
