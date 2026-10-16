/*
 * build.gradle.kts (nori-tc-ui-backend root)
 *
 * 목적: 서비스 저장소의 group/version 을 명시하고 중립 root convention 을 적용한다(SPEC §8.1, COM-026).
 * 제약:
 * - group/version 은 이 allprojects 한 곳이 명시해 선언이 없던 leaf 에도 전파한다. 서비스 version 은 기존 0.0.1-SNAPSHOT 그대로이며
 *   module 별 기존 group/version 선언도 같은 값으로 유지한다.
 * - 제품 서비스 root 이므로 층 1 금지 목록(group prefix)은 두지 않는다(SPEC §6.5).
 * - TC 전용 jacoco-conventions 는 root classpath 에만 올리고(apply false) java subproject 에 적용한다(기존 측정 동작 유지).
 */

plugins {
    id("com.nori.common.root-project-conventions")
    id("com.nori.tc.jacoco-conventions") apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "com.nori.tc"
    version = "0.0.1-SNAPSHOT"
}

// 리팩토링 캠페인 Phase 0(P0-1b): 커버리지 측정 도입 — 측정 전용, 게이트 없음.
// common build-logic 의 jacoco-conventions(includeBuild 공유)를 java 서브모듈에만 적용.
subprojects {
    pluginManager.withPlugin("java") {
        pluginManager.apply("com.nori.tc.jacoco-conventions")
    }
}
