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
 *     explicit core bean graph import
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
     * Starter는 조립 전용 모듈이므로 내부 어댑터와 core를 외부에 재노출하지 않습니다.
     */
    implementation(libs.spring.boot.starter.actuator)
    implementation("org.springframework.boot:spring-boot-actuator")
    implementation(libs.spring.boot.starter.data.redis)
    implementation("com.nori.tc:tc-common-runtime-support:0.0.1-SNAPSHOT")
    implementation(project(":ui:tc-ui-core"))
    implementation(project(":ui:adapter:tc-ui-web-adapter"))
    implementation(project(":ui:adapter:tc-ui-kafka-adapter"))
    implementation(project(":ui:adapter:tc-ui-db-adapter"))
    implementation(project(":ui:adapter:tc-ui-redis-adapter"))

    /*
     * AutoConfiguration 클래스 컴파일 의존성
     */
    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.context)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
