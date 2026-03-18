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
    api(project(":libs:ui:tc-ui-core"))

    /*
     * tc-db-core: Store/Domain 인터페이스 계약
     * JPA 구현 모듈(tc-db-jpa-common-schema)은 직접 참조하지 않습니다.
     * 런타임 주입은 tc-ui-backend-app(starter)에서 담당합니다.
     */
    implementation("com.nori.tc:tc-db-core:0.0.1-SNAPSHOT")

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
}

tasks.test {
    useJUnitPlatform()
}
