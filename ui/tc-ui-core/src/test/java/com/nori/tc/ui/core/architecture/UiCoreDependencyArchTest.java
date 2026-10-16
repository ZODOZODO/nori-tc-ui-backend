package com.nori.tc.ui.core.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * UI core 계층의 금지 의존성을 ArchUnit으로 검증합니다.
 *
 * <p>TC 잔류 messaging package 두 리터럴은 유지하고, 이관된 중립 messaging package 를 함께 금지합니다(SPEC §5.8.1).</p>
 */
@AnalyzeClasses(
        packages = "com.nori.tc.ui.core",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class UiCoreDependencyArchTest {

    @ArchTest
    static final ArchRule uiCoreShouldNotDependOnAdaptersStartersOrInfra = noClasses()
            .that()
            .resideInAnyPackage("com.nori.tc.ui.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.nori.tc.ui.adapter..",
                    "com.nori.tc.ui.adapters..",
                    "com.nori.tc.ui.starter..",
                    "com.nori.tc.db..",
                    "com.nori.tc.messaging.domain..",
                    "com.nori.tc.messaging.kafka.contract..",
                    "com.nori.common.messaging..",
                    "org.apache.kafka.."
            );
}
