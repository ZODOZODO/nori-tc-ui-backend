package com.nori.tc.ui.adapters.redis.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * 리팩토링 캠페인 Phase 4 재발 방지 게이트 — backlog 순서 324 의 재발을 차단한다.
 *
 * <p>Docker 가 없는 CI 러너(norilab)에서 무가드 Testcontainers 베이스는 static 초기화 실패로
 * 하위 테스트 전체를 연쇄 실패시켜 빌드를 통째로 막았다. 교정은 두 가지였다.
 * ① 컨테이너를 선언하는 클래스 전부에 {@code @Testcontainers(disabledWithoutDocker = true)},
 * ② 컨테이너를 <b>static 초기화 블록</b>에서 기동하는 클래스에 {@code isDockerAvailable()} 가드.
 *
 * <p>②를 static 초기화에만 요구하는 이유 — 애노테이션은 JUnit 이 테스트 실행을 막아줄 뿐 클래스 로드는
 * 막지 못한다. {@code @BeforeAll} 에서 기동하는 클래스는 애노테이션만으로 충분하지만,
 * static 블록에서 기동하면 클래스 로드 시점에 이미 터진다. 조건을 구분하지 않으면
 * {@code @BeforeAll} 방식 클래스에 불필요한 가드를 강요해 규칙이 잡음이 된다.
 *
 * <p>새 컨테이너 테스트가 가드를 빠뜨리면 Docker 가 있는 로컬에서는 통과하고 CI 에서만 터진다.
 * 규칙이 없으면 재발이 늦게 발견된다.
 */
@AnalyzeClasses(
        packages = "com.nori.tc.ui.adapters.redis",
        importOptions = ImportOption.OnlyIncludeTests.class
)
class TestcontainersDockerGuardArchTest {

    private static final String TESTCONTAINERS_ANNOTATION = "org.testcontainers.junit.jupiter.Testcontainers";
    private static final String DOCKER_CLIENT_FACTORY = "org.testcontainers.DockerClientFactory";
    private static final String TESTCONTAINERS_PACKAGE_PREFIX = "org.testcontainers.";

    private static final DescribedPredicate<JavaClass> DECLARE_TESTCONTAINER_FIELD =
            new DescribedPredicate<>("declare a Testcontainers container field") {
                @Override
                public boolean test(final JavaClass javaClass) {
                    return javaClass.getFields().stream()
                            .map(JavaField::getRawType)
                            .anyMatch(type -> type.getFullName().startsWith(TESTCONTAINERS_PACKAGE_PREFIX));
                }
            };

    private static final ArchCondition<JavaClass> BE_ANNOTATED_WITH_DISABLED_WITHOUT_DOCKER =
            new ArchCondition<>("be annotated with @Testcontainers(disabledWithoutDocker = true)") {
                @Override
                public void check(final JavaClass javaClass, final ConditionEvents events) {
                    final boolean satisfied = javaClass.tryGetAnnotationOfType(TESTCONTAINERS_ANNOTATION)
                            .map(annotation -> Boolean.TRUE.equals(annotation.get("disabledWithoutDocker").orElse(Boolean.FALSE)))
                            .orElse(Boolean.FALSE);
                    events.add(new SimpleConditionEvent(
                            javaClass,
                            satisfied,
                            javaClass.getName() + (satisfied ? " is " : " is not ")
                                    + "annotated with @Testcontainers(disabledWithoutDocker = true)"
                    ));
                }
            };

    private static final ArchCondition<JavaClass> GUARD_STATIC_CONTAINER_START =
            new ArchCondition<>("guard static-initializer container start with DockerClientFactory.isDockerAvailable()") {
                @Override
                public void check(final JavaClass javaClass, final ConditionEvents events) {
                    final JavaCodeUnit staticInitializer = javaClass.getStaticInitializer().orElse(null);
                    if (staticInitializer == null || !startsContainer(staticInitializer)) {
                        return;
                    }
                    final boolean satisfied = staticInitializer.getMethodCallsFromSelf().stream()
                            .anyMatch(call -> DOCKER_CLIENT_FACTORY.equals(call.getTargetOwner().getFullName())
                                    && "isDockerAvailable".equals(call.getName()));
                    events.add(new SimpleConditionEvent(
                            javaClass,
                            satisfied,
                            javaClass.getName() + (satisfied ? " guards " : " does not guard ")
                                    + "static container start with DockerClientFactory.isDockerAvailable()"
                    ));
                }

                private boolean startsContainer(final JavaCodeUnit codeUnit) {
                    return codeUnit.getMethodCallsFromSelf().stream()
                            .anyMatch(call -> "start".equals(call.getName())
                                    && call.getTargetOwner().getFullName().startsWith(TESTCONTAINERS_PACKAGE_PREFIX));
                }
            };

    @ArchTest
    static final ArchRule containerDeclaringTestsMustBeDisabledWithoutDocker =
            classes()
                    .that(DECLARE_TESTCONTAINER_FIELD)
                    .should(BE_ANNOTATED_WITH_DISABLED_WITHOUT_DOCKER)
                    .andShould(GUARD_STATIC_CONTAINER_START)
                    .because("Docker 미가용 러너에서 컨테이너 테스트가 빌드를 막지 않게 한다 (backlog 순서 324 재발 방지)");
}
