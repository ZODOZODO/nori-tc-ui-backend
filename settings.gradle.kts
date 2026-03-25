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
    includeBuild("../nori-tc-common/build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("com.nori.tc.settings-conventions")
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
