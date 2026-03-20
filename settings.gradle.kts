/*
 * settings.gradle.kts (nori-tc-ui-backend)
 *
 * UI Backend 레이어 라이브러리 레포 — UI Backend 모듈 전체를 관리합니다.
 * 각 모듈은 GitLab Package Registry에 Maven 좌표로 publish됩니다.
 *
 * 의존성 흐름
 * - nori-tc-common (80364130) : db/messaging/common 모듈 참조
 * - 모듈 간 내부 의존은 project() 참조
 */

rootProject.name = "nori-tc-ui-backend"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // 프로젝트 레벨 repositories {} 블록을 금지합니다.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        /*
         * 로컬 개발 환경 전용: -PuseLocalMaven 옵션으로 활성화합니다.
         * CI 환경에서는 이 옵션을 사용하지 않으므로 registry만 참조합니다.
         * 예) ./gradlew build -PuseLocalMaven
         */
        if (settings.providers.gradleProperty("useLocalMaven").isPresent) {
            mavenLocal()
        }

        /*
         * GitLab Package Registry 인증 정책
         * - CI 환경: GitLab이 자동 주입하는 CI_JOB_TOKEN 을 Job-Token 헤더로 사용합니다.
         * - 로컬 개발: gitlabToken gradle property 또는 GITLAB_TOKEN 환경변수를 Private-Token 헤더로 사용합니다.
         * - 교차 프로젝트 패키지 조회는 대상 프로젝트의 Job token permissions allowlist 설정이 선행되어야 합니다.
         */
        val ciJobToken = System.getenv("CI_JOB_TOKEN")
        val privateToken = settings.providers.gradleProperty("gitlabToken")
            .orElse(System.getenv("GITLAB_TOKEN") ?: "")
            .get()
        val authHeaderName = if (ciJobToken.isNullOrBlank()) "Private-Token" else "Job-Token"
        val authHeaderValue = if (ciJobToken.isNullOrBlank()) privateToken else ciJobToken

        // nori-tc-common (project ID: 80364130) — DB/Messaging/Common 모듈
        maven {
            name = "GitLabCommon"
            url = uri("https://gitlab.com/api/v4/projects/80364130/packages/maven")
            credentials(HttpHeaderCredentials::class) {
                name = authHeaderName
                value = authHeaderValue
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }

        mavenCentral()
    }
}

/* ===================================================
 * 1. UI Backend Base Modules
 * =================================================== */

include(":ui:tc-ui-domain")
project(":ui:tc-ui-domain").projectDir = file("ui/tc-ui-domain")

include(":ui:tc-ui-core")
project(":ui:tc-ui-core").projectDir = file("ui/tc-ui-core")

/* ===================================================
 * 2. UI Backend Adapter Modules
 * =================================================== */

include(":ui:adapter:tc-ui-db-adapter")
project(":ui:adapter:tc-ui-db-adapter").projectDir = file("ui/adapter/tc-ui-db-adapter")

include(":ui:adapter:tc-ui-kafka-adapter")
project(":ui:adapter:tc-ui-kafka-adapter").projectDir = file("ui/adapter/tc-ui-kafka-adapter")

include(":ui:adapter:tc-ui-redis-adapter")
project(":ui:adapter:tc-ui-redis-adapter").projectDir = file("ui/adapter/tc-ui-redis-adapter")

include(":ui:adapter:tc-ui-web-adapter")
project(":ui:adapter:tc-ui-web-adapter").projectDir = file("ui/adapter/tc-ui-web-adapter")

/* ===================================================
 * 3. UI Backend Starter Modules
 * =================================================== */

include(":ui:starter:tc-ui-backend-starter")
project(":ui:starter:tc-ui-backend-starter").projectDir = file("ui/starter/tc-ui-backend-starter")
