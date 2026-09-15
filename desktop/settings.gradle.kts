// 안드로이드 빌드와 **일부러 갈라 놓은 별도 빌드**다.
//
// 루트 build.gradle.kts 는 안드로이드 그레이들 플러그인을 걸어 두는데, 그것을 받으려면
// dl.google.com 이 열려 있어야 한다. 데스크톱 프로그램은 안드로이드와 아무 상관이 없으니
// 그 짐을 질 이유가 없다. 여기서는 Maven Central 과 플러그인 포털만 쓴다.
//
// 덕분에 안드로이드 SDK 가 없는 PC 에서도, 이 저장소를 개발하는 컨테이너에서도 빌드된다.
// 저장소 뿌리에서:  gradlew.bat -p desktop run
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "spell-desktop"

// 교정 엔진은 다시 만들지 않는다. 안드로이드 앱이 쓰는 그 모듈을 그대로 끌어다 쓴다.
// core/build.gradle.kts 는 코틀린 JVM 플러그인만 쓰므로 여기서도 그대로 붙는다.
include(":core")
project(":core").projectDir = file("../core")
