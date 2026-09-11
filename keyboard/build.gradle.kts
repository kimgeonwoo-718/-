plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 앱에 내장할 Gemini API 키.
 *
 * 소스에 적지 않는다 — 저장소가 공개라 적는 순간 유출이다. CI 비밀값(`GEMINI_API_KEY`)
 * 이나 로컬 `gradle.properties` 의 `geminiApiKey` 에서 읽는다. 둘 다 없으면 빈 값이고,
 * 그러면 앱은 사용자가 직접 넣은 키만 쓴다.
 */
val geminiApiKey: String =
    System.getenv("GEMINI_API_KEY")?.trim()
        ?: (project.findProperty("geminiApiKey") as String?)?.trim()
        ?: ""

/**
 * 바이너리에 그대로 박지 않고 XOR 로 섞는다. `BuiltInKey` 가 같은 마스크로 푼다.
 *
 * `strings` 한 방에 나오지 않게 하는 정도다. 마스크가 소스에 있으니 마음먹으면 풀린다 —
 * 진짜 방어는 구글 콘솔의 앱 제한(패키지명 + 서명)이고, 이건 시간을 버는 것뿐이다.
 */
fun obfuscate(key: String): String {
    val mask = "spell-keyboard-2026".toByteArray(Charsets.UTF_8)
    return key.toByteArray(Charsets.UTF_8)
        .mapIndexed { i, b -> (b.toInt() xor mask[i % mask.size].toInt()) and 0xFF }
        .joinToString("") { "%02x".format(it) }
}

/**
 * 서명 키.
 *
 * 없으면 안드로이드가 만들어 주는 디버그 키를 쓰는데, CI 러너마다 새로 만들어져서
 * 빌드할 때마다 서명이 바뀐다. 그러면 (1) 덮어쓰기 설치가 안 되고 (2) 구글 콘솔의
 * 앱 제한이 서명 SHA-1 을 보기 때문에 내장 키가 매번 거절당한다. 비밀값으로 고정한다.
 */
val keystoreFile: File? = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.spellkeyboard.ko"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.spellkeyboard.ko"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "GEMINI_KEY_OBF", "\"${obfuscate(geminiApiKey)}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreFile != null) {
            getByName("debug") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 한글 오토마타와 교정 엔진. 안드로이드에 의존하지 않는 순수 Kotlin 모듈이다.
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
