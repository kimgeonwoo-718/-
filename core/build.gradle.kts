import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 안드로이드 모듈과 같은 바이트코드 레벨을 맞춘다.
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
    // AI 교정은 REST 호출 한 번이라 SDK 를 쓰지 않는다. 서버용 SDK 를 넣으면
    // HTTP 클라이언트와 JSON 라이브러리가 딸려 와 APK 가 몇 MB 씩 붙는다.
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
