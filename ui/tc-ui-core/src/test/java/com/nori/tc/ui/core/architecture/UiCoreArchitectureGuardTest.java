package com.nori.tc.ui.core.architecture;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * `tc-ui-core` 모듈의 아키텍처 경계를 검증하는 가드 테스트입니다.
 *
 * <p>핵심 규칙:</p>
 * <p>1) core main 소스는 DB domain 타입을 직접 import하지 않습니다.</p>
 * <p>2) core 모듈은 DB domain 아티팩트에 직접 의존하지 않습니다.</p>
 */
class UiCoreArchitectureGuardTest {

    /**
     * core main 소스에 DB domain import가 다시 유입되지 않는지 검증합니다.
     *
     * @throws IOException 파일 순회/읽기 실패 시 예외
     */
    @Test
    void uiCoreMainSourcesShouldNotImportDbDomain() throws IOException {
        final List<String> violations = collectForbiddenTokenViolations(
                Path.of("src", "main", "java"),
                List.of("import com.nori.tc.db.domain.")
        );

        Assertions.assertTrue(
                violations.isEmpty(),
                () -> "tc-ui-core main 소스에 DB domain import가 남아 있습니다." + System.lineSeparator()
                        + String.join(System.lineSeparator(), violations)
        );
    }

    /**
     * build script에 tc-db-domain 직접 의존이 없는지 검증합니다.
     *
     * @throws IOException 파일 읽기 실패 시 예외
     */
    @Test
    void uiCoreBuildScriptShouldNotDependOnDbDomainArtifact() throws IOException {
        final Path buildScriptPath = Path.of("build.gradle.kts");
        final List<String> lines = Files.readAllLines(buildScriptPath, StandardCharsets.UTF_8);
        final List<String> violations = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i).trim();
            final boolean dependencyDeclaration = line.startsWith("api(")
                    || line.startsWith("implementation(")
                    || line.startsWith("compileOnly(")
                    || line.startsWith("runtimeOnly(");
            if (dependencyDeclaration && line.contains("tc-db-domain")) {
                violations.add((i + 1) + ": " + line);
            }
        }

        Assertions.assertTrue(
                violations.isEmpty(),
                () -> "tc-ui-core build.gradle.kts 에 tc-db-domain 직접 의존이 남아 있습니다."
                        + System.lineSeparator()
                        + String.join(System.lineSeparator(), violations)
        );
    }

    /**
     * Java 소스에서 금지 토큰 사용 라인을 수집합니다.
     *
     * @param sourceRoot 검사 루트
     * @param forbiddenTokens 금지 토큰 목록
     * @return 위반 목록
     * @throws IOException 파일 순회 실패 시 예외
     */
    private static List<String> collectForbiddenTokenViolations(
            final Path sourceRoot,
            final List<String> forbiddenTokens
    ) throws IOException {
        final List<String> violations = new ArrayList<>();
        if (Files.notExists(sourceRoot)) {
            return violations;
        }

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> scanFile(sourceRoot, path, forbiddenTokens, violations));
        }

        return violations;
    }

    /**
     * 단일 파일을 읽어 금지 토큰 포함 라인을 위반 목록에 누적합니다.
     *
     * @param sourceRoot 상대 경로 기준 루트
     * @param file 검사 대상 파일
     * @param forbiddenTokens 금지 토큰 목록
     * @param violations 누적 위반 목록
     */
    private static void scanFile(
            final Path sourceRoot,
            final Path file,
            final List<String> forbiddenTokens,
            final List<String> violations
    ) {
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                final String line = lines.get(lineIndex);
                for (String token : forbiddenTokens) {
                    final String trimmedLine = line.stripLeading();
                    final boolean matched = token.startsWith("import ")
                            ? trimmedLine.startsWith(token)
                            : line.contains(token);
                    if (matched) {
                        violations.add(sourceRoot.relativize(file) + ":" + (lineIndex + 1) + " -> " + line.trim());
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("소스 파일 읽기 실패: " + file, ex);
        }
    }
}
