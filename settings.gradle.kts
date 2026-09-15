pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// 한동안 이 저장소에는 상관없는 프로젝트가 둘 들어 있었다. 청각장애인용 소음 알림 앱이
// 먼저 있었고(첫 커밋 041eb5d) 맞춤법 키보드가 그 위에서 가지를 쳤다. 그래서 이 가지에도
// 그쪽 `app/` 모듈과 "DeafNoiseAlert" 라는 이름이 딸려 와 있었다.
//
// 이 가지에서는 그것을 걷어 냈다. 소음 알림 앱은 자기 가지
// (claude/deaf-noise-alert-app-m387hq)에 그대로 살아 있다 — 여기서 지웠다고 없어지지 않는다.
rootProject.name = "SpellKeyboard"
include(":core")
include(":keyboard")
