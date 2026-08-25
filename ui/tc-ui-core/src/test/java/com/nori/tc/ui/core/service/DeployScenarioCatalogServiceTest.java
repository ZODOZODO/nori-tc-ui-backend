package com.nori.tc.ui.core.service;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalogMeta;
import com.nori.tc.ui.core.port.db.DeployScenarioCatalogMetaPort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployScenarioCatalogServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void startupLoadFailsFastWhenScenarioYamlIsInvalid() throws Exception {
        Files.writeString(tempDir.resolve("invalid.yaml"), """
                version: 1
                scenario:
                  code:
                  name: Invalid
                  summary: broken
                appliesTo:
                  eventTypes:
                    - EQP_UPDATE
                steps:
                  - order: 10
                    code: STEP_1
                    name: Broken
                    targetApp: BUSINESS
                    description: broken
                    run:
                      action: UPDATE_MODEL_BINDING
                      timeoutMs: 1000
                      replyRequired: true
                """);

        final DeployScenarioCatalogService service = new DeployScenarioCatalogService(
                deployProperties(tempDir),
                new InMemoryCatalogMetaPort(),
                objectMapper,
                com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard.disabled()
        );

        final IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                service::initializeCatalog
        );

        assertTrue(exception.getMessage().contains("startup validation failed"));
    }

    @Test
    void startupLoadFallsBackToClasspathCatalogWhenDirectoryDoesNotExist() {
        final DeployScenarioCatalogService service = new DeployScenarioCatalogService(
                deployProperties(tempDir.resolve("missing-scenarios")),
                new InMemoryCatalogMetaPort(),
                objectMapper,
                com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard.disabled()
        );

        service.initializeCatalog();

        assertTrue(service.currentCatalog().scenarioCount() > 0);
        assertNotNull(service.currentCatalog().catalogChecksum());
    }

    @Test
    void reloadKeepsPreviousCatalogWhenNewSourceValidationFails() throws Exception {
        final Path scenarioFile = tempDir.resolve("eqp-update.yaml");
        Files.writeString(scenarioFile, validScenarioYaml());

        final DeployScenarioCatalogService service = new DeployScenarioCatalogService(
                deployProperties(tempDir),
                new InMemoryCatalogMetaPort(),
                objectMapper,
                com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard.disabled()
        );
        service.initializeCatalog();

        final String beforeChecksum = service.currentCatalog().catalogChecksum();
        final int beforeVersion = service.currentCatalog().catalogVersion();

        Files.writeString(scenarioFile, "version: 1\nscenario: [");

        final var result = service.reload("테스트 재적재", "tester");

        assertFalse(result.success());
        assertEquals(beforeVersion, result.catalogVersionBefore());
        assertEquals(beforeVersion, result.catalogVersionAfter());
        assertEquals(beforeChecksum, service.currentCatalog().catalogChecksum());
        assertFalse(result.validationErrors().isEmpty());
    }

    private UiDeployProperties deployProperties(final Path scenarioDirectory) {
        return new UiDeployProperties(
                scenarioDirectory.toString(),
                100,
                10,
                20,
                20,
                1000L,
                30000L,
                86400L,
                86400L,
                3900L,
                4200L,
                30,
                90
        );
    }

    /**
     * 순서 338 — step 의 {@code run:} 을 값 없이 쓴 운영자 YAML 이 필드 경로를 담은 검증 오류로 거부되는지 본다.
     *
     * <p>키 자체를 빼면 {@code RawStep.run} 의 기본 인스턴스가 남아 이미 {@code step.run.action} 오류가 났지만,
     * {@code run:} 을 빈 값으로 쓰면 Jackson 이 그 기본 인스턴스를 null 로 덮어 무가드 역참조가 helpful NPE
     * ("Cannot read field \"action\" because \"rawStep.run\" is null")로 새어 나갔다 — 측정된 수정 전 메시지다.
     * 카탈로그 디렉터리는 운영자가 채우는 외부 입력이므로 경계에서 필드 경로와 함께 거부해야 한다.
     */
    @Test
    void startupLoadRejectsStepWithEmptyRunBlockWithFieldPath() throws Exception {
        Files.writeString(tempDir.resolve("empty-run.yaml"), """
                version: 1
                scenario:
                  code: EQP_UPDATE_EMPTY_RUN
                  name: EQP Update With Empty Run
                  summary: run 값이 빈 경우
                appliesTo:
                  eventTypes:
                    - EQP_UPDATE
                steps:
                  - order: 10
                    code: STEP_1
                    name: Empty Run
                    targetApp: BUSINESS
                    description: run 키는 있고 값이 비어 있다.
                    run:
                """);

        final DeployScenarioCatalogService service = new DeployScenarioCatalogService(
                deployProperties(tempDir),
                new InMemoryCatalogMetaPort(),
                objectMapper,
                com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard.disabled()
        );

        final IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                service::initializeCatalog
        );

        assertTrue(
                exception.getMessage().contains("step.run 값이 비어 있습니다"),
                "필드 경로를 담은 검증 오류여야 한다. 실제: " + exception.getMessage()
        );
    }

    private String validScenarioYaml() {
        return """
                version: 1
                scenario:
                  code: EQP_UPDATE_GENERIC
                  name: EQP Update Generic
                  summary: 테스트용 update scenario
                appliesTo:
                  eventTypes:
                    - EQP_UPDATE
                steps:
                  - order: 10
                    code: UPDATE_MODEL_BINDING
                    name: Binding Update
                    targetApp: BUSINESS
                    description: binding을 전환한다.
                    run:
                      action: UPDATE_MODEL_BINDING
                      timeoutMs: 10000
                      replyRequired: true
                      retry:
                        maxAttempts: 1
                        backoffMs: 0
                    rollback:
                      enabled: true
                      mode: AUTO_COMPENSATABLE
                      action: RESTORE_PREVIOUS_MODEL_BINDING
                """;
    }

    private static final class InMemoryCatalogMetaPort implements DeployScenarioCatalogMetaPort {

        private DeployScenarioCatalogMeta latest;
        private int sequence = 0;

        @Override
        public Optional<DeployScenarioCatalogMeta> findLatest() {
            return Optional.ofNullable(latest);
        }

        @Override
        public DeployScenarioCatalogMeta append(
                final String checksum,
                final int scenarioCount,
                final String actor,
                final String reloadReason
        ) {
            latest = new DeployScenarioCatalogMeta(
                    ++sequence,
                    checksum,
                    scenarioCount,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    actor,
                    reloadReason
            );
            return latest;
        }
    }
}
