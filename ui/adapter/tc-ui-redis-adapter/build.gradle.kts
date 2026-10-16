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
 *   - UiRedisConfiguration      : @Bean("businessRedisTemplate") typed,
 *                                 @Bean("dlqRedisTemplate") / @Bean("commLogRedisTemplate") typeless
 * Service:
 *   - GatewayDlqRedisService    : tc:gateway:dlq:* 읽기/삭제,
 *                                 tc:gateway:quarantine:* 읽기 (dlqRedisTemplate)
 *   - BusinessDlqRedisService   : tc:business:core:dlq:* 읽기/삭제 (dlqRedisTemplate)
 *   - UiSessionCacheService     : tc:ui:backend:session:{token} TTL=300s (businessRedisTemplate)
 *   - AsyncResultStoreService   : tc:ui:backend:async:{traceId} TTL=600s (businessRedisTemplate)
 */

plugins {
    `java-library`
    // Docker 미가용 CI 에서 무가드 Testcontainers 베이스가 빌드를 통째로 막는 재발을 차단합니다.
    id("com.nori.common.archunit-conventions")
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
    implementation("com.nori.tc:tc-db-domain:0.0.3")

    /*
     * 공유 shard-group 매핑(tc.shard-groups[*]) — Gateway shard lease 점유 현황 조회에서
     * group id / owned-partitions 정의를 Gateway와 동일한 properties로 읽습니다.
     * TC 잔류 SharedShardGroupProperties 와 이동한 중립 SharedShardGroup 을 함께 씁니다(SPEC §8.1 #81 SPLIT_BOTH).
     */
    implementation("com.nori.tc:tc-common-shard:0.0.3")
    implementation("com.nori.common:nori-common-foundation:0.0.4")

    /*
     * 클러스터 조정 원장(§8) — operation ledger / resource barrier / membership / fenced lease /
     * catalog head 의 Lua 원자 연산과 canonical digest 를 공통 구현으로 사용합니다.
     * core 는 이 모듈에 의존하지 않고 port 로만 표현하며, 번역은 이 어댑터가 담당합니다.
     * TC 잔류 store·script 와 이동한 중립 조정 타입을 함께 공개합니다(SPEC §8.1 #82 SPLIT_BOTH).
     */
    api("com.nori.tc:tc-common-coordination:0.0.3")
    api("com.nori.common:nori-delivery-support:0.0.4")

    /*
     * Spring Data Redis: RedisTemplate, LettuceConnectionFactory, RedisSerializer 등
     * - spring.data.redis.* 표준 설정을 읽되, RedisConnectionFactory 빈은 직접 구성합니다.
     * - businessRedisTemplate(typed) / dlqRedisTemplate·commLogRedisTemplate(typeless, 적재 측 mode 일치).
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
    /*
     * GatewayDlqRedisServiceQuarantineTest 는 RedisTemplate / ValueOperations 를
     * mock 으로 stub 합니다. spring-boot-starter-test 는 Mockito + AssertJ 를
     * transitive 로 가져오며, web-adapter 와 동일한 테스트 컨벤션을 공유합니다.
     */
    testImplementation(libs.spring.boot.starter.test)

    /*
     * §23.2/§23.3/§23.8 — 조정 원장은 Lua 원자성과 TTL 만료가 본질이라 mock 으로는 검증할 수 없다.
     * 이 모듈의 기존 mock-only 컨벤션에 대한 의도적 예외이며, Docker 미가용 CI 에서는
     * @Testcontainers(disabledWithoutDocker = true) 로 자동 skip 된다.
     */
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
