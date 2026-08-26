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
    /*
     * 공유 shard-group 매핑(tc.shard-groups[*]) — 조정 shard lease 조회 빈의 조립 인자입니다.
     * 조정 어댑터의 빈 등록은 module config 가 아니라 이 starter 의 조정 configuration 이 맡습니다
     * (UiRedisAdapterModuleConfiguration 주석 참조 — 같은 포트 후보 2 개로 기동이 깨진 사례).
     */
    implementation("com.nori.tc:tc-common-shard:0.0.1-SNAPSHOT")
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
    compileOnly(libs.jackson.annotations)

    testImplementation(libs.spring.boot.starter.test)
    /*
     * starter 테스트는 자동 구성 클래스를 직접 컴파일/로딩하면서
     * 외부 클래스 파일에 기록된 Jackson annotation 메타데이터까지 함께 해석합니다.
     * main 쪽 compileOnly 의존성은 testCompileClasspath로 전파되지 않으므로,
     * 테스트 컴파일 경고(unknown enum constant Include.NON_EMPTY)를 막기 위해
     * annotation jar를 test 스코프에 명시적으로 포함합니다.
     */
    testImplementation(libs.jackson.annotations)
    /*
     * ModelFlowStarterMockMvcTest 가 MockMvc 로 4 endpoint 의 HTTP 응답을 검증합니다.
     * spring-boot-starter-webmvc-test 는 spring-web (MediaType, MockMvcBuilders 등)
     * 의 컴파일/런타임 의존을 testCompileClasspath 에 전파합니다 — main 의 web-adapter
     * 는 implementation 스코프이므로 spring-web 이 test 스코프로 자동 노출되지 않습니다.
     */
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
