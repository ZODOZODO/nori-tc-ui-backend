package com.nori.tc.ui.adapters.web.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * 컨트롤러가 조정 원장 전용 빈을 직접 주입받지 못하게 고정합니다.
 *
 * <p>{@code com.nori.tc.ui.core.coordination} 최상위 패키지의 서비스/런타임 빈은
 * {@code UiCoordinationConfiguration} 이 <b>조정이 켜진 구성에만</b> 등록합니다. 컨트롤러가 그 타입을
 * 생성자로 직접 받으면 조정 비활성 구성에서 {@code UnsatisfiedDependencyException} 으로 <b>앱 전체가
 * 기동하지 못합니다</b> — 그 구성에서 그 엔드포인트만 못 쓰는 게 아니라 프로세스가 죽습니다.</p>
 *
 * <p>2026-08-26 프로덕션 배포 실패가 정확히 이 형태였습니다({@code AsyncResultController} →
 * {@code UiOperationQueryService}). 기존 테스트는 조정 configuration 만 따로 띄웠고 web 계층을 함께
 * 조립하지 않아 이 결함을 잡지 못했습니다. 그래서 조립 대신 <b>주입 형태</b>를 규칙으로 고정합니다.</p>
 *
 * <p>허용 형태는 {@code ObjectProvider<T>} 입니다 — 빈이 없으면 {@code getIfAvailable()} 이 null 을
 * 돌려주고 컨트롤러가 503 으로 답합니다.</p>
 */
@AnalyzeClasses(
        packages = "com.nori.tc.ui.adapters.web",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class CoordinationBeanInjectionArchTest {

    /** 조정이 켜진 구성에만 존재하는 빈들이 사는 패키지(하위 model/port 는 값 타입이라 제외). */
    private static final String COORDINATION_PACKAGE = "com.nori.tc.ui.core.coordination";

    @ArchTest
    static final com.tngtech.archunit.lang.ArchRule controllersMustNotInjectCoordinationBeansDirectly =
            classes()
                    .that(new DescribedPredicate<JavaClass>("are REST controllers") {
                        @Override
                        public boolean test(final JavaClass javaClass) {
                            return javaClass.getName().endsWith("Controller");
                        }
                    })
                    .should(new ArchCondition<>("not take coordination beans as constructor parameters") {
                        @Override
                        public void check(final JavaClass javaClass, final ConditionEvents events) {
                            for (final JavaConstructor constructor : javaClass.getConstructors()) {
                                for (final JavaClass parameter : constructor.getRawParameterTypes()) {
                                    if (isCoordinationBean(parameter)) {
                                        events.add(SimpleConditionEvent.violated(
                                                javaClass,
                                                javaClass.getName() + " 가 조정 전용 빈 "
                                                        + parameter.getSimpleName()
                                                        + " 을 생성자로 직접 받습니다 — 조정 비활성 구성에서 앱이"
                                                        + " 기동하지 못합니다. ObjectProvider<"
                                                        + parameter.getSimpleName() + "> 로 받으십시오."));
                                    }
                                }
                            }
                        }
                    });

    /**
     * 조정 전용 빈 타입인지 판정합니다.
     *
     * <p>{@code coordination.model} / {@code coordination.port} 하위는 값 타입·포트 인터페이스라
     * 조정 여부와 무관하게 참조할 수 있으므로 제외합니다.</p>
     *
     * @param type 생성자 파라미터 타입
     * @return 조정 전용 빈이면 true
     */
    private static boolean isCoordinationBean(final JavaClass type) {
        final String name = type.getPackageName();
        return name.equals(COORDINATION_PACKAGE);
    }
}
