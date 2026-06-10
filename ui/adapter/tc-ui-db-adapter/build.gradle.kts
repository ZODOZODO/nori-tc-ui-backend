/*
 * tc-ui-db-adapter
 *
 * 역할
 * - tc-ui-core의 DB Port 인터페이스(UserPort, SessionPort, PermissionPort)를 구현합니다.
 * - JPA 기술에 직접 의존하지 않고 tc-db-core Store 추상화를 통해 DB에 접근합니다.
 *   (gateway-db-adapter / business-db-adapter 와 동일한 구조)
 *
 * Port 구현체:
 *   - JpaUserPort        : TcUserInfoStore 사용
 *   - JpaSessionPort     : TcUiAuthSessionStore 사용
 *   - JpaPermissionPort  : TcUserGroupMemberStore / TcUserGroupPermissionStore / TcUiPermissionStore 사용
 *   - UiEqpRoutePartitionDbAdapter : U13
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
     * DB 어댑터는 코어 Port를 구현하므로 코어를 공개 의존성으로 노출합니다.
     */
    implementation(project(":ui:tc-ui-core"))

    /*
     * tc-db-core: Store/Domain 인터페이스 계약
     * JPA 구현 모듈(tc-db-jpa-common-schema)은 직접 참조하지 않습니다.
     * 런타임 주입은 tc-ui-backend-app(starter)에서 담당합니다.
     */
    implementation("com.nori.tc:tc-db-core:0.0.1-SNAPSHOT")

    /*
     * 공유 shard-group 매핑 Resolver — PASSIVE listener-group 검증에서 같은 shard-group 의 partition 들을 허용합니다.
     */
    implementation("com.nori.tc:tc-common-shard:0.0.1-SNAPSHOT")

    implementation("org.springframework:spring-jdbc")
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    /*
     * Spring 컴포넌트/설정 바인딩 컴파일 의존성
     */
    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.tx)
    compileOnly(libs.jakarta.annotation.api)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.2.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    /*
     * EqpPlcPollGroupAdapter 의 @Version optimistic-lock 동시성(L1344) 검증 전용 test-scope 의존성.
     * - 실 H2 + 공통 JPA store(TcEqpPlcJpaStore)로 단일 트랜잭션 read-modify-write + 충돌 재시도가
     *   동시 GROUP_START lost-update 를 막는지 실 스레드로 증명한다(mock 으로는 @Version 1차캐시 의미를 못 살림).
     * - production 무영향(test scope only) · common 무변경(이미 publishToMavenLocal 된 좌표 소비).
     */
    testImplementation("com.nori.tc:tc-db-jpa-common-schema:0.0.1-SNAPSHOT")
    testImplementation(libs.spring.boot.starter.data.jpa)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("com.h2database:h2")
}

tasks.test {
    useJUnitPlatform()
}
