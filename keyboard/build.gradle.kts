plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * AI 중계 서버 주소.
 *
 * 앱에는 Gemini 키가 없다. 앱은 구글 대신 이 서버로 보내고, 서버가 키를 붙여 넘긴다.
 * CI 변수 `AI_SERVER_URL` 이나 로컬 `gradle.properties` 의 `aiServerUrl` 에서 읽는다.
 * 비어 있으면 AI 기능이 통째로 꺼진다(`Prefs.aiAvailable`).
 *
 * 사용자가 자기 키를 넣는 칸은 2026-09-12 에 없앴다. 일반 사용자에게 "구글에서 키를
 * 발급받아 오라" 고 할 수는 없다. 그 시절 이야기가 주석에 남아 있어서 지운다.
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
 * 구글 로그인 클라이언트 ID(웹 종류).
 *
 * 비밀값이 아니다 — APK 를 뜯으면 어차피 보이는 공개 식별자다. 그래도 저장소에는 안
 * 넣는다. 프로젝트마다 다른 값이고, 서버의 `GOOGLE_CLIENT_IDS` 와 **짝이 맞아야** 한다.
 * 비어 있으면 설정 화면의 로그인 행이 통째로 사라진다(`Prefs.loginAvailable`).
 */
val googleClientId: String = (System.getenv("GOOGLE_CLIENT_ID")
    ?: (project.findProperty("googleClientId") as String?)
    ?: "").trim()

/**
 * 서명 키.
 *
 * 없으면 안드로이드가 만들어 주는 디버그 키를 쓰는데, CI 러너마다 새로 만들어져서
 * 빌드할 때마다 서명이 바뀐다. 그러면 덮어쓰기 설치가 안 되고, Play 에 올릴 수도 없다.
 * 비밀값으로 고정한다.
 */
/**
 * Kiwi 형태소 분석기.
 *
 * 공백을 아예 안 친 글을 푸는 데 우리 엔진보다 확실히 낫다(같은 말뭉치 3,000 문장에서
 * 경계 F1 87.6% → 96.1%, 문장 통째 44.2% → 66.2%). 그 자리에만 쓴다 —
 * 멀쩡한 문장에 들이대면 합성어를 쪼갠다.
 *
 * AAR(10MB)과 모델(84MB)은 **저장소에 넣지 않고 빌드할 때 받는다.** 둘 다 남의 산출물이고,
 * 합쳐 94MB 를 git 에 넣으면 clone 이 그만큼 무거워진다.
 */
val kiwiVersion = "v0.23.2"
val kiwiHome: File = layout.buildDirectory.dir("kiwi").get().asFile
val kiwiAar: File = File(kiwiHome, "kiwi-android-$kiwiVersion.aar")
val kiwiModelAssets: File = File(kiwiHome, "assets")

/** 받다 만 파일을 쓰지 않게, 임시 이름으로 받고 다 받은 뒤에 옮긴다. */
fun download(url: String, target: File) {
    if (target.exists() && target.length() > 0) return
    target.parentFile.mkdirs()
    val partial = File(target.parentFile, target.name + ".part")
    logger.lifecycle("Kiwi 내려받는 중: $url")
    uri(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
    check(partial.length() > 0) { "받은 파일이 비었다: $url" }
    partial.renameTo(target)
}

val fetchKiwi by tasks.registering {
    description = "Kiwi AAR 과 모델을 받아 둔다"
    outputs.dir(kiwiHome)
    // 받은 것이 그대로 있으면 다시 받지 않는다.
    outputs.upToDateWhen { kiwiAar.exists() && File(kiwiModelAssets, "kiwi/sj.morph").exists() }
    doLast {
        val base = "https://github.com/bab2min/Kiwi/releases/download/$kiwiVersion"
        download("$base/kiwi-android-$kiwiVersion.aar", kiwiAar)

        val modelDir = File(kiwiModelAssets, "kiwi")
        if (!File(modelDir, "sj.morph").exists()) {
            val tgz = File(kiwiHome, "model.tgz")
            download("$base/kiwi_model_${kiwiVersion}_base.tgz", tgz)
            modelDir.mkdirs()
            // 꾸러미 안은 models/cong/base/* 다. 경로를 납작하게 펴서 넣는다 —
            // 앱은 이 폴더 하나만 통째로 꺼내 쓴다.
            //
            // 바깥 tar 를 부르지 않는다. 그래들에 들어 있는 것으로 풀면 러너에 tar 가
            // 있든 없든, 설정 캐시가 켜지든 말든 똑같이 돈다.
            copy {
                from(tarTree(resources.gzip(tgz))) {
                    include("models/cong/base/*")
                    eachFile { path = name }
                }
                into(modelDir)
                includeEmptyDirs = false
            }
            check(File(modelDir, "cong.mdl").exists()) { "모델을 못 풀었다: $modelDir" }
        }
    }
}

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
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")

        // 번역기(ML Kit)가 CPU 종류마다 네이티브 라이브러리를 하나씩 들고 온다. 네 벌이
        // 다 들어가면 그것만 62MB 다. x86 과 x86_64 는 **에뮬레이터 전용**이라 실제 폰에서는
        // 한 바이트도 쓰이지 않는데 34MB 를 차지한다. 빼면 APK 가 그만큼 줄어든다.
        //
        // armeabi-v7a(32비트 ARM)는 남긴다. 오래된 보급형 폰이 아직 이걸 쓴다.
        // 에뮬레이터로 시험할 일이 생기면 여기에 "x86_64" 를 잠깐 도로 넣으면 된다.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // 받아 놓은 Kiwi 모델을 에셋으로 싣는다. 앱이 처음 쓸 때 파일로 꺼내 놓고
    // 그 경로를 Kiwi 에 넘긴다 — 네이티브 쪽이 파일을 mmap 해야 해서 스트림으로는 안 된다.
    sourceSets["main"].assets.srcDir(kiwiModelAssets)

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
    // Kiwi. arm64-v8a 에만 네이티브 라이브러리가 있어서 32비트 폰에서는 안 올라온다 —
    // 그때는 기존 형태소 사전으로 돌아간다(KiwiSpacer 참고).
    implementation(files(kiwiAar))

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

    // 구글 로그인. 계정 고르는 창을 띄워 **ID 토큰** 하나를 받는 데까지만 쓴다 —
    // 그 토큰이 진짜인지는 서버가 구글 공개키로 확인한다(docs/ACCOUNTS.md).
    // 옛 GoogleSignIn API 대신 Credential Manager 를 쓴다. 그쪽은 2025 년에 접혔다.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // 온디바이스 번역 (구글 ML Kit). 언어팩은 처음 쓸 때 기기가 내려받고, 번역 자체는
    // 기기 안에서만 돈다 — 서버도 AI 한도도 쓰지 않는다.
    implementation("com.google.mlkit:translate:17.0.3")
}

// 에셋을 모으기 전에 받아 둬야 한다. preBuild 에 걸면 모든 변형(variant)에 다 걸린다.
tasks.named("preBuild") { dependsOn(fetchKiwi) }
