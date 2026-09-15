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
        "--java-options", "-Dfile.encoding=UTF-8"
    )
    doLast {
        println("나왔다: ${outDir.absolutePath}")
    }
}
