plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spellkeyboard.ko"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.spellkeyboard.ko"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
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
        // Anthropic SDK 가 java.time / Optional 같은 최신 표준 라이브러리를 쓴다.
        // minSdk 24 기기에서도 돌게 하려면 디슈가링이 필요하다.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            // Anthropic SDK 가 Apache HttpComponents 를 끌고 오는데, 그 jar 들이
            // 저마다 같은 이름의 메타데이터를 담고 있어 APK 로 합칠 때 충돌한다.
            // 실행에 쓰이지 않는 파일들이라 빼면 된다.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }
}

dependencies {
    // 한글 오토마타와 교정 엔진. 안드로이드에 의존하지 않는 순수 Kotlin 모듈이다.
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
