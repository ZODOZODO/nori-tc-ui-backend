/*
 * tc-ui-core
 *
 * 역할
 * - UI Backend의 Port 인터페이스, UseCase, Registry를 정의합니다.
 * - 기술 중립적인 계층으로, 특정 Kafka/JPA/Redis 구현에 의존하지 않습니다.
 *
 * 주요 구성 (Phase 2에서 구현)
 * Port (DB):
 *   - UserPort           : tc_user_info CRUD
 *   - SessionPort        : tc_ui_auth_session CRUD
 *   - PermissionPort     : tc_ui_permission + 그룹 권한 조회
 *   - TokenCachePort     : 토큰 캐싱 (business Redis)
 *   - AsyncResultStorePort: eqp_start/end 결과 임시 저장
 *   - UiGatewayEqpRoutePartitionLookupPort: tc_eqp.route_partition 조회 (U13)
 * Port (Kafka 발행):
 *   - UiGatewayEventPublishPort  : tc.ui.events.gateway
 *   - UiBusinessEventPublishPort : tc.ui.events.business
 * Port (Kafka 수신):
 *   - UiCommandIngressPort : tc.ui.commands 수신 처리
 * UseCase:
 *   - LoginUseCase, LogoutUseCase, ValidateTokenUseCase
 * Registry:
 *   - DualResponseRegistry : eqp_create/update/delete DeferredResult 관리
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
     * 도메인 모델(AuthToken, UserPrincipal, UiTaskResult)을 Port 시그니처에서 사용합니다.
     */
    api(project(":ui:tc-ui-domain"))

    /*
     * DB Core 공통 계약(PageRequest, Upsert Command)을 관리 Port 시그니처에서 사용합니다.
     * api로 선언하여 Adapter 계층에서 동일 계약 타입을 재사용할 수 있도록 노출합니다.
     */
    api("com.nori.tc:tc-db-core:0.0.1-SNAPSHOT")

    /*
     * GatewayEquipmentProfileSnapshot을 Port 시그니처(UiCommandMessage)에서 사용합니다.
     * GatewayEquipmentProfileSnapshot은 순수 Kafka payload DTO이므로 tc-messaging-kafka-contract에 위치합니다.
     */
    api("com.nori.tc:tc-messaging-kafka-contract:0.0.1-SNAPSHOT")

    /*
     * Kafka source 상수(TcKafkaSources.UI_BACKEND 등)를 web-adapter에서 사용합니다.
     * api로 선언하여 web-adapter가 별도 의존성 없이 메시지 도메인 상수를 참조할 수 있도록 노출합니다.
     */
    api("com.nori.tc:tc-messaging-domain:0.0.1-SNAPSHOT")

    /*
     * Deploy orchestration T3:
     * - JsonNode 기반 desired state merge / step plan 직렬화를 위해 Jackson을 직접 사용합니다.
     * - scenario YAML 파일을 fail-fast 로딩하기 위해 SnakeYAML을 사용합니다.
     */
    implementation(libs.jackson.databind)
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.nori.tc:tc-common-runtime-support:0.0.1-SNAPSHOT")

    /*
     * Phase 4 OPS-01:
     * - DualResponseRegistry 메트릭(카운터/타이머) 기록을 위해 Micrometer API를 사용합니다.
     * - 실제 registry 구현체는 앱 계층(Actuator/Prometheus)에서 주입합니다.
     */
    implementation(libs.micrometer.core)

    /*
     * Spring 생명주기/설정 바인딩 컴파일 의존성
     * - @Component, @Service 등 어노테이션은 컴파일 시에만 필요합니다.
     */
    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.context)
    compileOnly(libs.jakarta.annotation.api)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
