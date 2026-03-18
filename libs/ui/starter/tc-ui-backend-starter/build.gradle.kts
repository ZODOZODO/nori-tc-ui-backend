/*
 * tc-ui-backend-starter
 *
 * 역할
 * - tc-ui-domain/core + 어댑터 4종을 apps/tc-ui-backend-app에 한 번에 조립합니다.
 * - AutoConfiguration을 통해 Spring 컨텍스트에 UI Backend 빈을 자동 등록합니다.
 *
 * 주요 구성 (Phase 7에서 구현)
 *   - TcUiBackendAutoConfiguration
 *     @AutoConfiguration
 *     @ComponentScan("com.nori.tc.ui.core")
 *     @Import({
 *         UiWebAdapterAutoConfiguration,
 *         UiKafkaAdapterAutoConfiguration,
 *         UiRedisAdapterAutoConfiguration,
 *         UiDbAdapterAutoConfiguration
 *     })
 *   - resources/META-INF/spring/
 *       org.springframework.boot.autoconfigure.AutoConfiguration.imports 등록
 *
 * 의존성 구조
 *   apps/tc-ui-backend-app
 *     └── tc-ui-backend-starter (이 모듈)
 *           ├── tc-ui-web-adapter   (Spring Security + REST Controller)
 *           ├── tc-ui-kafka-adapter (Publisher + Subscriber)
 *           ├── tc-ui-db-adapter    (JPA Repository Port 구현)
 *           └── tc-ui-redis-adapter (Dual Redis Service)
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
     * Starter는 어댑터 4종을 api로 노출하여 앱이 컨텍스트 없이 어댑터 타입을 사용할 수 있게 합니다.
     */
    api(project(":libs:ui:adapter:tc-ui-web-adapter"))
    api(project(":libs:ui:adapter:tc-ui-kafka-adapter"))
    api(project(":libs:ui:adapter:tc-ui-db-adapter"))
    api(project(":libs:ui:adapter:tc-ui-redis-adapter"))

    /*
     * AutoConfiguration 클래스 컴파일 의존성
     */
    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.context)

    /*
     * Spring Boot 4.x는 Jackson 3.x(tools.jackson)를 기본으로 사용하므로
     * com.fasterxml.jackson.databind.ObjectMapper 빈이 자동 등록되지 않습니다.
     * TcUiBackendAutoConfiguration에서 Jackson 2.x ObjectMapper 빈을 명시 제공하기 위해
     * 컴파일 의존성으로 선언합니다. 런타임에는 tc-ui-web-adapter의 의존성으로 제공됩니다.
     */
    compileOnly(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
