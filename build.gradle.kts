plugins {
    id("com.nori.tc.root-project-conventions")
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

// 리팩토링 캠페인 Phase 0(P0-1b): 커버리지 측정 도입 — 측정 전용, 게이트 없음.
// common build-logic 의 jacoco-conventions(includeBuild 공유)를 java 서브모듈에만 적용.
subprojects {
    pluginManager.withPlugin("java") {
        pluginManager.apply("com.nori.tc.jacoco-conventions")
    }
}
