/*
 * settings.gradle.kts (nori-tc-ui-backend)
 *
 * UI Backend 레이어 라이브러리 레포 — UI Backend 모듈 전체를 관리합니다.
 * 각 모듈은 GitLab Package Registry에 Maven 좌표로 publish됩니다.
 *
 * 의존성 흐름
 * - nori-tc-common (80364130) : db/messaging/common 잔류 모듈 참조(고정 raw GAV com.nori.tc:*:0.0.3)
 * - nori-common               : 제품 중립 공통 기반(고정 raw GAV com.nori.common:*:0.0.4)
 * - 모듈 간 내부 의존은 project() 참조
 *
 * build-logic 해석(SPEC §8.1 소유권 표·root 표 nori-tc-ui-backend 행)
 * - mode 는 nori.buildLogic.mode(허용값 composite, published)이고 property 가 없으면 nori.release.repository 가 있을 때
 *   published, 없을 때 composite 로 유도한다(유일한 정본 규칙).
 * - NoriRelease repository 는 nori.release.repository 값이 있을 때만 pluginManagement 첫 항목으로 이 settings 본문이 직접 등록한다.
 * - composite 는 ../nori-common/build-logic(이름 nori-common-build-logic)과 ../nori-tc-common/build-logic
 *   (이름 nori-tc-common-build-logic)을 include 하고, ../nori-common/build-logic 에는 nori-tc-common/build-logic/settings.gradle.kts
 *   와 같은 com.nori.common:nori-build-logic 명시 dependencySubstitution 을 선언한다. published 는 build-logic include 0개다.
 * - marker 고정 version 은 common 6개 0.0.4, TC 2개 0.0.3 이다(FIRST-PARTY-PROVENANCE-CORRECTION 유효 version, PLAN 7.6).
 * - 제품 composite 는 없다(INCLUDED_PRODUCT_BUILD=0).
 */

pluginManagement {
    // pluginManagement 블록은 따로 평가되므로 필요한 값과 함수는 이 블록 안에서만 정의한다.
    val noriReleaseRepository: String? = providers.gradleProperty("nori.release.repository").orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val requestedMode: String? = providers.gradleProperty("nori.buildLogic.mode").orNull
    val noriBuildLogicMode: String = when {
        requestedMode == null -> if (noriReleaseRepository != null) "published" else "composite"
        requestedMode == "composite" || requestedMode == "published" -> requestedMode
        else -> throw GradleException(
            "NORI_BUILD_LOGIC_MODE_INVALID: nori.buildLogic.mode 는 composite 또는 published 여야 합니다: $requestedMode"
        )
    }
    if (noriBuildLogicMode == "published" && noriReleaseRepository == null) {
        throw GradleException(
            "NORI_RELEASE_REPOSITORY_REQUIRED: nori.buildLogic.mode=published 에는 -Pnori.release.repository=<absolute path> 가 필요합니다."
        )
    }

    // local repository URL 은 absolute path 를 file: URI 로 정규화한 값만 허용한다(SPEC §8.1).
    fun noriReleaseRepositoryUri(value: String): java.net.URI {
        val file = try {
            if (value.startsWith("file:", ignoreCase = true)) java.io.File(java.net.URI(value)) else java.io.File(value)
        } catch (e: IllegalArgumentException) {
            throw GradleException("nori.release.repository 는 absolute path 또는 absolute file: URI 여야 합니다: $value", e)
        }
        if (!file.isAbsolute) {
            throw GradleException("nori.release.repository 는 absolute path 또는 absolute file: URI 여야 합니다: $value")
        }
        return file.toURI()
    }

    if (noriBuildLogicMode == "composite") {
        for (buildLogicPath in listOf("../nori-common/build-logic", "../nori-tc-common/build-logic")) {
            val buildLogicDirectory = java.io.File(settingsDir, buildLogicPath).canonicalFile
            if (!buildLogicDirectory.isDirectory) {
                throw GradleException(
                    "NORI_COMPOSITE_BUILD_MISSING: composite mode 에 필요한 build-logic source build 가 없습니다: ${buildLogicDirectory.absolutePath}"
                )
            }
        }
        includeBuild("../nori-common/build-logic") {
            name = "nori-common-build-logic"
        }
        includeBuild("../nori-tc-common/build-logic") {
            name = "nori-tc-common-build-logic"
        }
    }

    repositories {
        if (noriReleaseRepository != null) {
            maven {
                name = "NoriRelease"
                url = noriReleaseRepositoryUri(noriReleaseRepository)
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }

    // 중립 convention plugin 6개와 TC 잔류 plugin 2개의 고정 marker version(SPEC §8.1 marker 집합 common 6 + TC 2).
    plugins {
        id("com.nori.common.settings-conventions") version "0.0.4"
        id("com.nori.common.root-project-conventions") version "0.0.4"
        id("com.nori.common.java-module-conventions") version "0.0.4"
        id("com.nori.common.dependency-matrix-conventions") version "0.0.4"
        id("com.nori.common.archunit-conventions") version "0.0.4"
        id("com.nori.common.security-scan-conventions") version "0.0.4"
        id("com.nori.tc.app-composition-conventions") version "0.0.3"
        id("com.nori.tc.jacoco-conventions") version "0.0.3"
    }
}

plugins {
    id("com.nori.common.settings-conventions")
}

rootProject.name = "nori-tc-ui-backend"

/* ===================================================
 * 0. build-logic composite 치환(SPEC §8.1 root 표 아래 문단)
 * =================================================== */

// pluginManagement 의 mode 값은 이 본문에서 보이지 않으므로 같은 유도 규칙으로 다시 읽는다(허용값 검사는 위에서 끝났다).
val buildLogicReleaseRepository: String? = providers.gradleProperty("nori.release.repository").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val buildLogicMode: String = providers.gradleProperty("nori.buildLogic.mode").orNull
    ?: if (buildLogicReleaseRepository != null) "published" else "composite"

// nested TC build-logic 이 같은 디렉터리를 include 할 때와 이름·치환 규칙이 한 가지로 일치하도록 같은 치환을 선언한다.
// 발행 artifactId(nori-build-logic)가 공급 build 의 rootProject.name(nori-common-build-logic)과 달라 기본 치환으로는 바뀌지 않는다.
if (buildLogicMode == "composite") {
    includeBuild("../nori-common/build-logic") {
        name = "nori-common-build-logic"
        dependencySubstitution {
            substitute(module("com.nori.common:nori-build-logic")).using(project(":"))
        }
    }

    // composite source 해석 identity 보정(AMD-f8f42ce383, nori-tc-gateway root 와 같은 구성): TC plugin(nori-tc-common-build-logic)을
    // 싣는 project buildscript classpath 는 그 canonical 구현 의존 com.nori.common:nori-build-logic 을 전이로 해석한다. 같은 디렉터리가
    // pluginManagement includeBuild 로 먼저 등록되면 위 명시 치환이 효과가 없으므로 buildscript configuration 에만 같은 대응을 선언해
    // source build 기본 identity(유효 common version 0.0.4)로 해석하게 한다. repository fallback 이 아니며 published 경로는 바꾸지 않는다.
    gradle.beforeProject {
        buildscript.configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                substitute(module("com.nori.common:nori-build-logic"))
                    .using(module("com.nori.common:nori-common-build-logic:0.0.4"))
                    .because("composite source identity of nori-common/build-logic (AMD-f8f42ce383)")
            }
        }
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
