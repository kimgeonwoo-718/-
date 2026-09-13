plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * AI 중계 서버 주소.
 *
 * 앱에는 Gemini 키가 없다. 사용자가 자기 키를 넣지 않으면 이 서버로 보내고, 서버가
 * 키를 붙여 구글로 넘긴다. CI 변수 `AI_SERVER_URL` 이나 로컬 `gradle.properties` 의
 * `aiServerUrl` 에서 읽는다. 비어 있으면 앱은 사용자가 직접 넣은 키로만 AI 교정을 한다.
 */
val aiServerUrl: String = run {
    val raw = System.getenv("AI_SERVER_URL")
        ?: (project.findProperty("aiServerUrl") as String?)
        ?: ""
    // 사람이 붙여넣은 값이다. 앞뒤 공백은 물론 줄바꿈·따옴표·"Value:" 같은 군더더기가
    // 섞여 들어온 적이 있다. 주소처럼 생긴 조각 하나만 건지고 나머지는 버린다.
    Regex("""https?://[A-Za-z0-9.\-]+(?::\d+)?(?:/[^\s"'\\]*)?""")
        .find(raw)?.value?.trimEnd('/') ?: ""
}

/**
 * 서명 키.
 *
 * 없으면 안드로이드가 만들어 주는 디버그 키를 쓰는데, CI 러너마다 새로 만들어져서
 * 빌드할 때마다 서명이 바뀐다. 그러면 덮어쓰기 설치가 안 되고, Play 에 올릴 수도 없다.
 * 비밀값으로 고정한다.
 */
val keystoreFile: File? = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.spellkeyboard.ko"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.spellkeyboard.ko"
        minSdk = 24
        targetSdk = 34
        // Play 는 올릴 때마다 versionCode 가 커져야 한다. CI 가 실행 번호를 넣어 준다.
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()) ?: 1
        versionName = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1"

        buildConfigField("String", "AI_SERVER_URL", "\"$aiServerUrl\"")
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
            // Play 에 올리는 릴리스 번들(.aab)도 같은 키로 서명한다. Play 에서는 이 키가
            // "업로드 키" 가 되고, 실제 설치본은 Play 가 자기 키로 다시 서명한다.
            create("release") {
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
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
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
    // 사진 고르기(PickVisualMedia — 저장소 권한 없이 시스템 선택창을 띄운다)와 EXIF 회전.
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Google Play 구독. 결제창을 띄우고 구매 토큰을 받는 것까지만 한다 —
    // "구독자인가" 는 서버가 그 토큰을 Play 에 물어 판단한다.
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    // 온디바이스 번역 (구글 ML Kit). 언어팩은 처음 쓸 때 기기가 내려받고, 번역 자체는
    // 기기 안에서만 돈다 — 서버도 AI 한도도 쓰지 않는다.
    implementation("com.google.mlkit:translate:17.0.3")
}
