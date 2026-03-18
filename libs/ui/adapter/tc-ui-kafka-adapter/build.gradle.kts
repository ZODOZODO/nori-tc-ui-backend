/*
 * tc-ui-kafka-adapter
 *
 * 역할
 * - tc.ui.events.gateway / tc.ui.events.business 발행 Port를 구현합니다.
 * - tc.ui.commands 구독 후 DualResponseRegistry 또는 AsyncResultStore에 결과를 저장합니다.
 *
 * 주요 구성 (Phase 5에서 구현)
 * Publisher:
 *   - UiGatewayEventKafkaPublisher  → tc.ui.events.gateway
 *     * route_partition 조회 후 ProducerRecord(topic, partition, key, payload) 명시 발행 (U13)
 *     * 미배정/음수 partition 시 발행 차단 + ERROR 로그
 *   - UiBusinessEventKafkaPublisher → tc.ui.events.business (일반 send)
 * Subscriber:
 *   - UiCommandKafkaSubscriber ← tc.ui.commands
 *     * Consumer Group: tc-ui-backend-group, MANUAL_IMMEDIATE
 *     * EQP_CREATE/UPDATE/DELETE → DualResponseRegistry
 *     * EQP_START_REP/EQP_END_REP → AsyncResultStorePort (Business Redis)
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
     * Kafka 어댑터는 코어 Port를 구현하고 UseCase를 호출합니다.
     */
    api(project(":libs:ui:tc-ui-core"))

    /*
     * Kafka 메시지 계약 (KafkaUiTaskMessage, KafkaUiTaskReplyMessage, KafkaUiTaskReplyEventType)
     * - 기존 tc-messaging-kafka-contract에 이미 정의된 계약을 재사용합니다.
     */
    implementation("com.nori.tc:tc-messaging-kafka-contract:0.0.1-SNAPSHOT")

    /*
     * Spring Kafka: KafkaTemplate, @KafkaListener, ProducerRecord 등
     */
    implementation(libs.spring.kafka)
    implementation(libs.jackson.databind)
    implementation(libs.micrometer.core)

    /*
     * Spring 컴포넌트/설정 바인딩 컴파일 의존성
     */
    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.boot.autoconfigure)
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
