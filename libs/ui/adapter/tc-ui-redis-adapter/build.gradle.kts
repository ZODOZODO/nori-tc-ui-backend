/*
 * tc-ui-redis-adapter
 *
 * 역할
 * - Gateway Redis(6379)와 Business Redis(6380)에 동시 접속하는 어댑터입니다.
 * - spring.data.redis.* 미사용: 직접 LettuceConnectionFactory를 생성하여 2개 인스턴스를 독립 관리합니다.
 *
 * 주요 구성 (Phase 4에서 구현)
 * Redis 설정:
 *   - UiRedisProperties         : @ConfigurationProperties 바인딩 (gateway/business 각각)
 *   - UiRedisConfiguration      : @Bean("gatewayRedisTemplate"), @Bean("businessRedisTemplate")
 * Service:
 *   - GatewayDlqRedisService    : tc:comm:gateway:dlq:* 읽기/삭제,
 *                                 tc:comm:gateway:quarantine:* 읽기 (gatewayRedisTemplate)
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
     * - UiAuthProperties(tokenCacheTtlSeconds) 도 이 모듈을 통해 사용합니다.
     */
    api(project(":libs:ui:tc-ui-core"))

    /*
     * Redis DLQ/Quarantine 엔트리 역직렬화 계약입니다.
     * - RedisDlqEntry, RedisQuarantineEntry, RedisBusinessDlqEntry 클래스가
     *   tc-db-domain의 redis 하위 패키지에 위치합니다.
     * - JDK 직렬화 특성상 쓰기(Gateway/Business 어댑터)와 읽기(UI 어댑터) 측 모두
     *   동일 패키지·클래스명을 참조해야 ClassNotFoundException 없이 역직렬화됩니다.
     * - 이 단일 의존성으로 tc-comm-gateway-redis-adapter, tc-business-redis-adapter 에 대한
     *   크로스 도메인 어댑터 결합이 완전히 제거됩니다.
     */
    implementation("com.nori.tc:tc-db-domain:0.0.1-SNAPSHOT")

    /*
     * Spring Data Redis: RedisTemplate, LettuceConnectionFactory, RedisSerializer 등
     * - spring.data.redis.* 자동설정은 비활성화하고, 직접 빈을 구성합니다.
     * - gatewayRedisTemplate, businessRedisTemplate 두 개를 독립 생성합니다.
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
