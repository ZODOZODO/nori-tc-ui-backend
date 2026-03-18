/*
 * 루트 build.gradle.kts (nori-tc-ui-backend)
 *
 * - 모든 java-library 서브프로젝트에 maven-publish 를 일괄 적용합니다.
 * - GitLab Package Registry publish 설정을 중앙에서 관리합니다.
 * - Spring Boot BOM import, Java 공통 설정도 여기서 일괄 적용합니다.
 */

import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "com.nori.tc"
    version = "0.0.1-SNAPSHOT"
}

subprojects {
    /*
     * Spring Boot BOM — io.spring.dependency-management 플러그인 적용 모듈에만 주입합니다.
     */
    pluginManager.withPlugin("io.spring.dependency-management") {
        extensions.configure(DependencyManagementExtension::class.java) {
            imports {
                mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
            }
        }
    }

    /*
     * Java 공통 설정 — java 플러그인 적용 모듈에만 적용합니다.
     */
    pluginManager.withPlugin("java") {
        extensions.configure(org.gradle.api.plugins.JavaPluginExtension::class.java) {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        dependencies {
            add("compileOnly", libs.slf4j.api)
        }

        tasks.withType(JavaCompile::class.java).configureEach {
            options.encoding = "UTF-8"
            if (!options.compilerArgs.contains("-parameters")) {
                options.compilerArgs.add("-parameters")
            }
        }

        tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform()
        }
    }

    /*
     * Maven Publish — java-library 플러그인 적용 모듈에 일괄 적용합니다.
     * GitLab Package Registry 단일 프로젝트(nori-tc-ui-backend)에 모든 모듈을 publish합니다.
     *
     * CI 환경  : CI_PROJECT_ID, CI_JOB_TOKEN 자동 주입
     * 로컬 환경: ~/.gradle/gradle.properties 에 아래 두 항목 설정
     *   gitlabToken=<PAT(api scope)>
     *   ciProjectId=<nori-tc-ui-backend GitLab project ID>
     */
    pluginManager.withPlugin("java-library") {
        apply(plugin = "maven-publish")

        afterEvaluate {
            extensions.configure(PublishingExtension::class.java) {
                publications {
                    create<MavenPublication>("mavenJava") {
                        from(components["java"])
                        // Spring BOM 관리 의존성의 버전을 빌드 해석 결과로 POM에 명시합니다.
                        // 이를 통해 Gradle 9.x의 'dependencies-without-versions' 검증 오류를 방지합니다.
                        versionMapping {
                            usage("java-api") { fromResolutionResult() }
                            usage("java-runtime") { fromResolutionResult() }
                        }
                    }
                }
                repositories {
                    maven {
                        name = "GitLab"
                        val projectId = System.getenv("CI_PROJECT_ID")
                            ?: (project.findProperty("ciProjectId") as String?)
                            ?: error("CI_PROJECT_ID 환경변수 또는 ciProjectId gradle property 가 필요합니다.")
                        url = uri("https://gitlab.com/api/v4/projects/$projectId/packages/maven")
                        credentials(HttpHeaderCredentials::class) {
                            val ciToken = System.getenv("CI_JOB_TOKEN")
                            if (ciToken != null) {
                                name = "Job-Token"
                                value = ciToken
                            } else {
                                name = "Private-Token"
                                value = (project.findProperty("gitlabToken") as String?)
                                    ?: error("gitlabToken gradle property 가 필요합니다. (~/.gradle/gradle.properties)")
                            }
                        }
                        authentication {
                            create<HttpHeaderAuthentication>("header")
                        }
                    }
                }
            }
        }
    }
}
