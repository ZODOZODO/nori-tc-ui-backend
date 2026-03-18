/*
 * tc-ui-domain
 *
 * 역할
 * - UI Backend 전용 순수 도메인 객체(POJO)를 정의합니다.
 * - 외부 기술 의존성(Spring, JPA, Kafka 등) 없음.
 * - 다른 모든 libs/ui 모듈이 이 모듈을 참조합니다.
 *
 * 주요 도메인 객체 (Phase 1에서 구현)
 * - AuthToken       : 발급된 토큰과 만료 정보
 * - UserPrincipal   : 인증된 사용자 (userPk, userId, 권한 코드 목록)
 * - UiTaskResult    : 비동기 작업 결과 (traceId, source, status, errorCode, errorMsg)
 */

plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

group = "com.nori.tc"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    /*
     * 도메인 계층에서 로깅 인터페이스를 사용할 수 있도록 SLF4J API를 노출합니다.
     * - 구현체(Logback)는 상위 계층(Starter/App)에서 제공합니다.
     */
    api(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
