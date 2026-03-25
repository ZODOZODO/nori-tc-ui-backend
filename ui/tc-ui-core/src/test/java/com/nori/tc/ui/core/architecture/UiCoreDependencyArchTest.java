package com.nori.tc.ui.core.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * UI core 계층의 금지 의존성을 ArchUnit으로 검증합니다.
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
                    "org.apache.kafka.."
            );
}
