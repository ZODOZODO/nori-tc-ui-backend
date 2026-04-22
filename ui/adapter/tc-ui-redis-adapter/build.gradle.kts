/*
 * tc-ui-redis-adapter
 *
 * 역할
 * - UI Backend가 사용하는 단일 Redis 연결 어댑터입니다.
 * - gateway/business 구분은 key prefix 기준의 논리 역할이며 물리 Redis 인스턴스는 1개입니다.
 * - Redis 연결값은 Spring Boot 표준 spring.data.redis.* 설정에서 읽습니다.
 * - RedisConnectionFactory는 직접 생성하고 기존 bean name은 alias로 유지합니다.
 *
 * 주요 구성
 * Redis 설정:
 *   - UiRedisProperties         : @ConfigurationProperties 바인딩 (spring.data.redis.*)
 *   - UiRedisConfiguration      : @Bean("gatewayRedisTemplate"), @Bean("businessRedisTemplate")
 * Service:
 *   - GatewayDlqRedisService    : tc:gateway:dlq:* 읽기/삭제,
 *                                 tc:gateway:quarantine:* 읽기 (gatewayRedisTemplate)
 *   - BusinessDlqRedisService   : tc:business:core:dlq:* 읽기/삭제 (businessRedisTemplate)
 *   - UiSessionCacheService     : tc:ui:backend:session:{token} TTL=300s (businessRedisTemplate)
 *   - AsyncResultStoreService   : tc:ui:backend:async:{traceId} TTL=600s (businessRedisTemplate)
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
     * Redis 어댑터는 코어 Port를 구현합니다.
     * - TokenCachePort, AsyncResultStorePort 구현체가 위치합니다.
     * - UiAuthProperties(tokenCacheTtlSeconds) 같은 모듈을 통해 사용합니다.
     */
    implementation(project(":ui:tc-ui-core"))

    /*
     * Redis DLQ/Quarantine 엔트리 역직렬화 계약입니다.
     * - RedisDlqEntry, RedisQuarantineEntry, RedisBusinessDlqEntry 클래스가
     *   tc-db-domain의 redis 하위 패키지에 위치합니다.
     * - JDK 직렬화 호환성 확인을 위해 gateway/business 어댑터가 저장한 클래스명을 참조합니다.
     * - 단일 의존성으로 gateway/business redis adapter에 대한 역참조를 피합니다.
     */
    implementation("com.nori.tc:tc-db-domain:0.0.1-SNAPSHOT")

    /*
     * Spring Data Redis: RedisTemplate, LettuceConnectionFactory, RedisSerializer 등
     * - spring.data.redis.* 표준 설정을 읽되, RedisConnectionFactory 빈은 직접 구성합니다.
     * - gatewayRedisTemplate, businessRedisTemplate은 논리 역할별 템플릿 이름입니다.
     */
    implementation(libs.spring.boot.starter.data.redis)
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
