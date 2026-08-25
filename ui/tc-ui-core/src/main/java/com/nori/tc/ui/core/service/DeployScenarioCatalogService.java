package com.nori.tc.ui.core.service;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.deploy.DeployDeltaKind;
import com.nori.tc.ui.core.deploy.DeployRollbackMode;
import com.nori.tc.ui.core.deploy.DeployScenarioAction;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalog;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalogMeta;
import com.nori.tc.ui.core.deploy.DeployScenarioCondition;
import com.nori.tc.ui.core.deploy.DeployScenarioDefinition;
import com.nori.tc.ui.core.deploy.DeployScenarioReloadResult;
import com.nori.tc.ui.core.deploy.DeployScenarioRetryPolicy;
import com.nori.tc.ui.core.deploy.DeployScenarioValidationResult;
import com.nori.tc.ui.core.deploy.DeployTargetApp;
import com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.DeployScenarioCatalogMetaPort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * deploy scenario YAML catalog를 관리합니다.
 *
 * <p>역할:</p>
 * <ul>
 *   <li>앱 기동 시 scenario 디렉터리 또는 classpath 기본 catalog를 읽습니다.</li>
 *   <li>파싱/정합성 검증에 실패하면 fail-fast 합니다.</li>
 *   <li>validate / reload / catalog admin API가 동일한 service를 사용합니다.</li>
 * </ul>
 */
@Service
public class DeployScenarioCatalogService {

    private static final Logger log = LoggerFactory.getLogger(DeployScenarioCatalogService.class);

    private static final String DEFAULT_ACTOR = "SYSTEM";
    private static final String STARTUP_LOAD_REASON = "STARTUP_LOAD";
    private static final String DEFAULT_RELOAD_REASON = "MANUAL_RELOAD";
    private static final String CLASSPATH_SCENARIO_INDEX = "deploy-scenarios/scenarios.index";
    private static final String CLASSPATH_SCENARIO_ROOT = "deploy-scenarios/";

    private final UiDeployProperties deployProperties;
    private final DeployScenarioCatalogMetaPort catalogMetaPort;
    private final ObjectMapper objectMapper;
    private final UiScenarioCatalogGuard catalogGuard;
    private final Yaml yaml;
    private final AtomicReference<DeployScenarioCatalog> catalogRef;
    private final ReentrantLock reloadLock;

    public DeployScenarioCatalogService(
            final UiDeployProperties deployProperties,
            final DeployScenarioCatalogMetaPort catalogMetaPort,
            final ObjectMapper objectMapper,
            final UiScenarioCatalogGuard catalogGuard
    ) {
        this.deployProperties = Objects.requireNonNull(deployProperties, "deployProperties is null");
        this.catalogMetaPort = Objects.requireNonNull(catalogMetaPort, "catalogMetaPort is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.catalogGuard = Objects.requireNonNull(catalogGuard, "catalogGuard is null");
        this.yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        this.catalogRef = new AtomicReference<>();
        this.reloadLock = new ReentrantLock();
    }

    /**
     * startup 시 scenario catalog를 적재합니다.
     */
    @PostConstruct
    public void initializeCatalog() {
        final CatalogCandidate candidate = loadCandidate();
        if (!candidate.validationErrors().isEmpty()) {
            throw new IllegalStateException("scenario catalog startup validation failed: "
                    + String.join(" | ", candidate.validationErrors()));
        }

        final DeployScenarioCatalogMeta meta = resolveCatalogMeta(
                candidate,
                DEFAULT_ACTOR,
                STARTUP_LOAD_REASON
        );
        final DeployScenarioCatalog catalog = candidate.toCatalog(meta);
        catalogRef.set(catalog);
        catalogGuard.onCatalogApplied(catalog.catalogChecksum());

        log.info("Deploy scenario catalog startup load 완료. version={}, checksum={}, scenarioCount={}, source={}",
                catalog.catalogVersion(), catalog.catalogChecksum(), catalog.scenarioCount(), candidate.sourceDescription());
    }

    /**
     * 현재 메모리에 적재된 catalog를 반환합니다.
     *
     * @return 현재 catalog
     */
    public DeployScenarioCatalog currentCatalog() {
        final DeployScenarioCatalog currentCatalog = catalogRef.get();
        if (currentCatalog == null) {
            throw new IllegalStateException("Deploy scenario catalog is not initialized");
        }
        return currentCatalog;
    }

    /**
     * 새 planning 을 접수하기 전에 이 Pod 의 catalog 가 클러스터 head 와 같은지 확인합니다(§13.9).
     *
     * <p>head 와 다르면 {@code 503 CATALOG_NOT_SYNCED} 로 거부합니다. scenario 디렉터리는 Pod-로컬
     * 외부 경로라 mismatch Pod 는 자동으로 새 catalog 를 얻지 못하며, 그 상태로 planning 을 받으면
     * 서로 다른 step 계획이 같은 설비에 적용됩니다.</p>
     *
     * @return head 와 일치하는 현재 catalog
     */
    public DeployScenarioCatalog currentCatalogForPlanning() {
        final DeployScenarioCatalog currentCatalog = currentCatalog();
        catalogGuard.requirePlanningAllowed(currentCatalog.catalogChecksum());
        return currentCatalog;
    }

    /**
     * 현재 설정된 source를 검증만 수행합니다.
     *
     * @param reason 운영 사유
     * @param actor 호출자
     * @return validate 결과
     */
    public DeployScenarioValidationResult validate(
            final String reason,
            final String actor
    ) {
        final CatalogCandidate candidate = loadCandidate();
        return new DeployScenarioValidationResult(
                candidate.validationErrors().isEmpty(),
                OffsetDateTime.now(ZoneOffset.UTC),
                candidate.scenarioCount(),
                candidate.validationErrors()
        );
    }

    /**
     * scenario catalog를 다시 읽고 검증 성공 시 교체합니다.
     *
     * @param reason 운영 사유
     * @param actor 호출자
     * @return reload 결과
     */
    public DeployScenarioReloadResult reload(
            final String reason,
            final String actor
    ) {
        if (!reloadLock.tryLock()) {
            throw new UiConflictException("scenario reload가 이미 진행 중입니다.");
        }

        try {
            final DeployScenarioCatalog before = currentCatalog();
            final CatalogCandidate candidate = loadCandidate();
            final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

            if (!candidate.validationErrors().isEmpty()) {
                return new DeployScenarioReloadResult(
                        false,
                        before.catalogVersion(),
                        before.catalogVersion(),
                        0,
                        now,
                        candidate.validationErrors()
                );
            }

            final DeployScenarioCatalogMeta meta = resolveCatalogMeta(
                    candidate,
                    normalizeActor(actor),
                    normalizeReason(reason, DEFAULT_RELOAD_REASON)
            );
            final DeployScenarioCatalog reloadedCatalog = candidate.toCatalog(meta);
            catalogRef.set(reloadedCatalog);
            /*
             * §13.9 — reload 는 반드시 head commit 을 동반한다. 로컬 AtomicReference 만 바꾸고 끝내면
             * 이 Pod 만 새 catalog 로 planning 하고 나머지는 옛 catalog 로 planning 한다.
             * validation 실패 경로는 위에서 이미 반환했으므로 여기 도달한 candidate 는 검증을 통과했다.
             */
            catalogGuard.onCatalogApplied(reloadedCatalog.catalogChecksum());

            log.info("Deploy scenario catalog reload 완료. beforeVersion={}, afterVersion={}, checksum={}, scenarioCount={}",
                    before.catalogVersion(),
                    reloadedCatalog.catalogVersion(),
                    reloadedCatalog.catalogChecksum(),
                    reloadedCatalog.scenarioCount());

            return new DeployScenarioReloadResult(
                    true,
                    before.catalogVersion(),
                    reloadedCatalog.catalogVersion(),
                    reloadedCatalog.scenarioCount(),
                    now,
                    List.of()
            );
        } finally {
            reloadLock.unlock();
        }
    }

    private DeployScenarioCatalogMeta resolveCatalogMeta(
            final CatalogCandidate candidate,
            final String actor,
            final String reason
    ) {
        final Optional<DeployScenarioCatalogMeta> latestOptional = catalogMetaPort.findLatest();
        if (latestOptional.isPresent() && latestOptional.get().catalogChecksum().equals(candidate.checksum())) {
            return latestOptional.get();
        }
        return catalogMetaPort.append(
                candidate.checksum(),
                candidate.scenarioCount(),
                normalizeActor(actor),
                normalizeReason(reason, STARTUP_LOAD_REASON)
        );
    }

    private CatalogCandidate loadCandidate() {
        final SourceLoadResult sourceLoadResult = loadScenarioSources();
        final List<String> errors = new ArrayList<>(sourceLoadResult.errors());

        if (sourceLoadResult.sources().isEmpty()) {
            if (errors.isEmpty()) {
                errors.add("scenario 파일을 찾을 수 없습니다. source=" + sourceLoadResult.sourceDescription());
            }
            return new CatalogCandidate(
                    sourceLoadResult.sourceDescription(),
                    "UNRESOLVED",
                    Map.of(),
                    errors
            );
        }

        final Map<String, DeployScenarioDefinition> scenarios = new LinkedHashMap<>();
        for (ScenarioSource source : sourceLoadResult.sources()) {
            try {
                final Object loaded = yaml.load(source.content());
                if (!(loaded instanceof Map<?, ?> loadedMap)) {
                    errors.add(source.name() + ": YAML 루트는 object 여야 합니다.");
                    continue;
                }

                final RawScenarioFile rawScenarioFile = objectMapper.convertValue(loadedMap, RawScenarioFile.class);
                final DeployScenarioDefinition definition = toScenarioDefinition(source.name(), rawScenarioFile);
                final String scenarioCode = definition.scenario().code();
                if (scenarios.containsKey(scenarioCode)) {
                    errors.add(source.name() + ": duplicated scenario code " + scenarioCode);
                    continue;
                }
                scenarios.put(scenarioCode, definition);
            } catch (IllegalArgumentException exception) {
                errors.add(source.name() + ": " + exception.getMessage());
            } catch (Exception exception) {
                errors.add(source.name() + ": " + exception.getMessage());
            }
        }

        return new CatalogCandidate(
                sourceLoadResult.sourceDescription(),
                computeChecksum(sourceLoadResult.sources()),
                scenarios,
                errors
        );
    }

    private SourceLoadResult loadScenarioSources() {
        final Path configuredDirectory = Path.of(deployProperties.scenarioDirectory()).toAbsolutePath().normalize();

        if (Files.exists(configuredDirectory)) {
            if (!Files.isDirectory(configuredDirectory)) {
                return new SourceLoadResult(
                        "filesystem:" + configuredDirectory,
                        List.of(),
                        List.of("scenario-directory가 디렉터리가 아닙니다. path=" + configuredDirectory)
                );
            }

            try {
                final List<ScenarioSource> filesystemSources = Files.list(configuredDirectory)
                        .filter(Files::isRegularFile)
                        .filter(path -> hasYamlExtension(path.getFileName().toString()))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .map(path -> new ScenarioSource(path.getFileName().toString(), readUtf8(path)))
                        .toList();

                return new SourceLoadResult(
                        "filesystem:" + configuredDirectory,
                        filesystemSources,
                        List.of()
                );
            } catch (IOException exception) {
                return new SourceLoadResult(
                        "filesystem:" + configuredDirectory,
                        List.of(),
                        List.of("scenario-directory를 읽을 수 없습니다. path="
                                + configuredDirectory + ", reason=" + exception.getMessage())
                );
            }
        }

        return loadClasspathSources();
    }

    private SourceLoadResult loadClasspathSources() {
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            return new SourceLoadResult(
                    "classpath:" + CLASSPATH_SCENARIO_ROOT,
                    List.of(),
                    List.of("classpath classLoader를 찾을 수 없습니다.")
            );
        }

        try (InputStream indexStream = classLoader.getResourceAsStream(CLASSPATH_SCENARIO_INDEX)) {
            if (indexStream == null) {
                return new SourceLoadResult(
                        "classpath:" + CLASSPATH_SCENARIO_ROOT,
                        List.of(),
                        List.of("classpath scenario index를 찾을 수 없습니다. resource=" + CLASSPATH_SCENARIO_INDEX)
                );
            }

            final List<String> fileNames = readUtf8(indexStream).lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .filter(line -> !line.startsWith("#"))
                    .toList();

            final List<ScenarioSource> sources = new ArrayList<>();
            final List<String> errors = new ArrayList<>();
            for (String fileName : fileNames) {
                final String resourcePath = CLASSPATH_SCENARIO_ROOT + fileName;
                try (InputStream scenarioStream = classLoader.getResourceAsStream(resourcePath)) {
                    if (scenarioStream == null) {
                        errors.add("classpath scenario resource를 찾을 수 없습니다. resource=" + resourcePath);
                        continue;
                    }
                    sources.add(new ScenarioSource(fileName, readUtf8(scenarioStream)));
                }
            }

            return new SourceLoadResult(
                    "classpath:" + CLASSPATH_SCENARIO_ROOT,
                    sources,
                    errors
            );
        } catch (IOException exception) {
            return new SourceLoadResult(
                    "classpath:" + CLASSPATH_SCENARIO_ROOT,
                    List.of(),
                    List.of("classpath scenario index를 읽을 수 없습니다. reason=" + exception.getMessage())
            );
        }
    }

    private DeployScenarioDefinition toScenarioDefinition(
            final String sourceName,
            final RawScenarioFile rawScenarioFile
    ) {
        if (rawScenarioFile == null) {
            throw new IllegalArgumentException("scenario 문서가 비어 있습니다.");
        }

        final DeployScenarioDefinition.Scenario scenario = new DeployScenarioDefinition.Scenario(
                requireText(sourceName, "scenario.code", rawScenarioFile.scenario.code),
                requireText(sourceName, "scenario.name", rawScenarioFile.scenario.name),
                requireText(sourceName, "scenario.summary", rawScenarioFile.scenario.summary)
        );

        final Set<UiCommandEventType> eventTypes = toEventTypes(sourceName, rawScenarioFile.appliesTo.eventTypes);
        final DeployScenarioCondition appliesToCondition = toCondition(sourceName, rawScenarioFile.appliesTo);
        final List<DeployScenarioDefinition.Step> steps = rawScenarioFile.steps.stream()
                .sorted(Comparator.comparingInt(rawStep -> rawStep.order))
                .map(rawStep -> toStepDefinition(sourceName, rawStep))
                .toList();

        return new DeployScenarioDefinition(
                rawScenarioFile.version,
                scenario,
                new DeployScenarioDefinition.AppliesTo(eventTypes, appliesToCondition),
                steps
        );
    }

    private DeployScenarioDefinition.Step toStepDefinition(
            final String sourceName,
            final RawStep rawStep
    ) {
        if (rawStep == null) {
            throw new IllegalArgumentException(sourceName + ": step 문서가 비어 있습니다.");
        }
        // YAML 이 `run:` 을 값 없이 쓰면 Jackson 이 RawStep.run 의 기본 인스턴스를 null 로 덮는다. 아래 run 필드
        // 역참조가 helpful NPE 로 새어 나가면 어느 필드가 문제인지 운영자가 알 수 없다(카탈로그는 외부 입력).
        if (rawStep.run == null) {
            throw new IllegalArgumentException(sourceName + ": step.run 값이 비어 있습니다.");
        }

        final DeployScenarioCondition when = toCondition(sourceName, rawStep.when);
        final RawRetry retry = rawStep.run.retry;
        final DeployScenarioRetryPolicy retryPolicy = retry == null
                ? DeployScenarioRetryPolicy.noRetry()
                : new DeployScenarioRetryPolicy(retry.maxAttempts, retry.backoffMs);

        final DeployScenarioDefinition.Run run = new DeployScenarioDefinition.Run(
                enumValue(sourceName, "step.run.action", rawStep.run.action, DeployScenarioAction.class),
                rawStep.run.timeoutMs,
                rawStep.run.replyRequired,
                retryPolicy
        );

        final DeployScenarioDefinition.Rollback rollback = rawStep.rollback == null
                ? DeployScenarioDefinition.Rollback.disabled()
                : new DeployScenarioDefinition.Rollback(
                        rawStep.rollback.enabled,
                        rawStep.rollback.enabled
                                ? enumValue(sourceName, "step.rollback.mode", rawStep.rollback.mode, DeployRollbackMode.class)
                                : null,
                        rawStep.rollback.enabled
                                ? enumValue(sourceName, "step.rollback.action", rawStep.rollback.action, DeployScenarioAction.class)
                                : null
                );

        return new DeployScenarioDefinition.Step(
                rawStep.order,
                requireText(sourceName, "step.code", rawStep.code),
                requireText(sourceName, "step.name", rawStep.name),
                enumValue(sourceName, "step.targetApp", rawStep.targetApp, DeployTargetApp.class),
                requireText(sourceName, "step.description", rawStep.description),
                when,
                run,
                rollback
        );
    }

    private DeployScenarioCondition toCondition(
            final String sourceName,
            final RawCondition rawCondition
    ) {
        if (rawCondition == null) {
            return DeployScenarioCondition.none();
        }
        return new DeployScenarioCondition(
                toDeltaKinds(sourceName, rawCondition.deltaAll),
                toDeltaKinds(sourceName, rawCondition.deltaAny),
                toDeltaKinds(sourceName, rawCondition.deltaNone)
        );
    }

    private Set<UiCommandEventType> toEventTypes(
            final String sourceName,
            final List<String> rawEventTypes
    ) {
        if (rawEventTypes == null || rawEventTypes.isEmpty()) {
            throw new IllegalArgumentException(sourceName + ": appliesTo.eventTypes가 비어 있습니다.");
        }
        return rawEventTypes.stream()
                .map(eventType -> {
                    try {
                        return UiCommandEventType.fromText(eventType);
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException(sourceName + ": unsupported eventType " + eventType, exception);
                    }
                })
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private Set<DeployDeltaKind> toDeltaKinds(
            final String sourceName,
            final List<String> rawDeltaKinds
    ) {
        if (rawDeltaKinds == null || rawDeltaKinds.isEmpty()) {
            return Set.of();
        }
        return rawDeltaKinds.stream()
                .map(deltaKind -> enumValue(sourceName, "delta", deltaKind, DeployDeltaKind.class))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private <T extends Enum<T>> T enumValue(
            final String sourceName,
            final String fieldName,
            final String rawValue,
            final Class<T> enumType
    ) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException(sourceName + ": " + fieldName + " 값이 비어 있습니다.");
        }
        try {
            return Enum.valueOf(enumType, rawValue.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(sourceName + ": unsupported " + fieldName + " " + rawValue, exception);
        }
    }

    private String computeChecksum(final List<ScenarioSource> sources) {
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            for (ScenarioSource source : sources.stream().sorted(Comparator.comparing(ScenarioSource::name)).toList()) {
                messageDigest.update(source.name().getBytes(StandardCharsets.UTF_8));
                messageDigest.update((byte) '\n');
                messageDigest.update(source.content().getBytes(StandardCharsets.UTF_8));
                messageDigest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }

    private static boolean hasYamlExtension(final String fileName) {
        final String normalized = fileName == null ? "" : fileName.trim().toLowerCase();
        return normalized.endsWith(".yaml") || normalized.endsWith(".yml");
    }

    private static String normalizeActor(final String actor) {
        if (actor == null || actor.isBlank()) {
            return DEFAULT_ACTOR;
        }
        return actor.trim();
    }

    private static String normalizeReason(final String reason, final String defaultValue) {
        if (reason == null || reason.isBlank()) {
            return defaultValue;
        }
        return reason.trim();
    }

    private static String requireText(
            final String sourceName,
            final String fieldName,
            final String rawValue
    ) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException(sourceName + ": " + fieldName + " 값이 비어 있습니다.");
        }
        return rawValue.trim();
    }

    private static String readUtf8(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("파일을 읽을 수 없습니다. path=" + path, exception);
        }
    }

    private static String readUtf8(final InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("입력 스트림을 읽을 수 없습니다.", exception);
        }
    }

    private record ScenarioSource(
            String name,
            String content
    ) {
    }

    private record SourceLoadResult(
            String sourceDescription,
            List<ScenarioSource> sources,
            List<String> errors
    ) {
        private SourceLoadResult {
            sources = List.copyOf(sources);
            errors = List.copyOf(errors);
        }
    }

    private record CatalogCandidate(
            String sourceDescription,
            String checksum,
            Map<String, DeployScenarioDefinition> scenarios,
            List<String> validationErrors
    ) {
        private CatalogCandidate {
            scenarios = Map.copyOf(scenarios);
            validationErrors = List.copyOf(validationErrors);
        }

        private int scenarioCount() {
            return scenarios.size();
        }

        private DeployScenarioCatalog toCatalog(final DeployScenarioCatalogMeta meta) {
            return new DeployScenarioCatalog(
                    meta.catalogVersion(),
                    meta.catalogChecksum(),
                    meta.loadedAt(),
                    meta.loadedBy(),
                    meta.reloadReason(),
                    scenarios
            );
        }
    }

    /**
     * YAML 파싱용 raw DTO입니다.
     */
    private static final class RawScenarioFile {
        public int version;
        public RawScenario scenario = new RawScenario();
        public RawCondition appliesTo = new RawCondition();
        public List<RawStep> steps = List.of();
    }

    private static final class RawScenario {
        public String code;
        public String name;
        public String summary;
    }

    private static final class RawCondition {
        public List<String> eventTypes = List.of();
        public List<String> deltaAll = List.of();
        public List<String> deltaAny = List.of();
        public List<String> deltaNone = List.of();
    }

    private static final class RawStep {
        public int order;
        public String code;
        public String name;
        public String targetApp;
        public String description;
        public RawCondition when;
        public RawRun run = new RawRun();
        public RawRollback rollback;
    }

    private static final class RawRun {
        public String action;
        public long timeoutMs;
        public boolean replyRequired;
        public RawRetry retry;
    }

    private static final class RawRetry {
        public int maxAttempts = 1;
        public long backoffMs = 0L;
    }

    private static final class RawRollback {
        public boolean enabled;
        public String mode;
        public String action;
    }
}
