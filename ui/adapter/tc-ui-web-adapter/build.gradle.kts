// tc-ui-web-adapter: Spring Security 설정 + REST 컨트롤러 (Phase 6)

plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
    // CoordinationBeanInjectionArchTest 용 archunit-junit5 제공.
    id("com.nori.common.archunit-conventions")
}

group = "com.nori.tc"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation(project(":ui:tc-ui-core"))
    // TraceIdGenerator 등 중립 runtime 도구(SPEC §8.1 #83 REPLACE, 유효 version common 0.0.4)
    implementation("com.nori.common:nori-common-runtime-support:0.0.4")
    // 공통 로깅 유틸리티: helper 외 MDC 직접 호출 금지(W4 ArchUnit) 준수를 위한 traceId 스코프 헬퍼
    implementation("com.nori.tc:tc-common-logging:0.0.3")
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    implementation(libs.jackson.databind)
    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.tx)
    compileOnly(libs.jakarta.annotation.api)
    annotationProcessor(libs.spring.boot.configuration.processor)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // W2-3: OpenApiDocsGenerationTest 가 /v3/api-docs 응답을 추적 경로(레포 루트 docs/api-reference)로 덤프한다.
    // rootDir = nori-tc-ui-backend 레포 루트(모듈 작업 디렉터리 의존 제거). 크로스-레포 쓰기 금지(소유 레포 내).
    systemProperty(
        "openapi.output.file",
        rootDir.resolve("docs/api-reference/openapi.json").absolutePath,
    )
}
