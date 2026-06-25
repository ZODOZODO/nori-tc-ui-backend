// tc-ui-web-adapter: Spring Security 설정 + REST 컨트롤러 (Phase 6)

plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

group = "com.nori.tc"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation(project(":ui:tc-ui-core"))
    implementation("com.nori.tc:tc-common-runtime-support:0.0.1-SNAPSHOT")
    // 공통 로깅 유틸리티: helper 외 MDC 직접 호출 금지(W4 ArchUnit) 준수를 위한 traceId 스코프 헬퍼
    implementation("com.nori.tc:tc-common-logging:0.0.1-SNAPSHOT")
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
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
}
