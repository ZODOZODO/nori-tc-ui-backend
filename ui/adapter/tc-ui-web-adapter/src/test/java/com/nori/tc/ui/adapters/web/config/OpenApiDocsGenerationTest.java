package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.adapters.web.controller.AuthController;
import com.nori.tc.ui.adapters.web.controller.support.UiAuthCookieFactory;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.logging.TcLogAutoConfiguration;
import com.nori.tc.ui.core.usecase.LoginUseCase;
import com.nori.tc.ui.core.usecase.LogoutUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W2-3·W2-4 — springdoc 가 노출하는 {@code /v3/api-docs} 가 200 을 반환하고 알려진 endpoint path 를
 * 포함하는지 검증한 뒤, 응답 본문을 추적 경로 {@code docs/api-reference/openapi.json} 으로 덤프한다.
 *
 * <p>이 모듈은 라이브러리(앱 어셈블리는 exec 레포)이고 운영 컨텍스트는 DB/Redis/Kafka 연결을 요구하므로,
 * 여기서는 최소 부트 컨텍스트(대표 컨트롤러 {@link AuthController} + 의존 mock + springdoc autoconfig)를
 * {@code MOCK} 환경으로 올려 외부 인프라 없이(Docker 불요) 스펙을 생성한다. springdoc 은 등록된 컨트롤러
 * 빈의 {@code @RequestMapping} 을 스캔하므로, 생성된 openapi.json 은 fixture 에 등록된 컨트롤러 표면을
 * 담는다(전 컨트롤러 표면 자동 생성은 앱 부팅/전체 포트 와이어링이 필요 — week22).</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = OpenApiDocsGenerationTest.OpenApiTestApp.class
)
class OpenApiDocsGenerationTest {

    private static final String KNOWN_PATH = "/api/auth/csrf";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // webAppContextSetup: 운영 DispatcherServlet handler mapping(springdoc 의 /v3/api-docs 포함)을 그대로 사용.
        // 보안 필터 체인은 적용하지 않으므로 문서 endpoint 가 인증 없이 도달한다.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void apiDocsEndpointExposesKnownPathAndIsDumpedToTrackedFile() throws Exception {
        final MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        final String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .as("생성된 OpenAPI 스펙은 알려진 endpoint path 를 포함해야 한다")
                .contains(KNOWN_PATH);
        assertThat(body)
                .as("OpenApiInfoConfig 의 커스터마이즈 title 이 적용되어야 한다(빈 미적용 시 기본 title)")
                .contains("TC MES UI Backend API");

        dumpToTrackedFile(body);
    }

    /**
     * /v3/api-docs 응답을 pretty-print 하여 추적 경로(빌드가 주입한 system property)로 덤프한다.
     *
     * <p>경로는 build.gradle.kts 의 {@code openapi.output.file}(rootDir 기준) 로 주입된다. 속성이 없으면
     * (IDE 단독 실행 등) 모듈 build 디렉터리로 폴백한다 — 추적 경로 생성은 Gradle test 실행이 담당.</p>
     */
    private void dumpToTrackedFile(final String body) throws IOException {
        final String configured = System.getProperty("openapi.output.file");
        final Path target = (configured != null && !configured.isBlank())
                ? Path.of(configured)
                : Path.of("build", "openapi", "openapi.json");

        final ObjectMapper mapper = new ObjectMapper();
        final String pretty = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(mapper.readTree(body));

        Files.createDirectories(target.getParent());
        Files.writeString(target, pretty, StandardCharsets.UTF_8);
    }

    /**
     * 최소 부트 컨텍스트. classpath 에 DB/Redis/Kafka starter 가 없으므로 @EnableAutoConfiguration 은
     * web mvc + security + springdoc 만 활성화한다. 문서 endpoint 는 MockMvc(보안 필터 미적용)로 도달한다.
     */
    @SpringBootConfiguration
    // TcLogAutoConfiguration 은 tc.logging.compress.* 필수 프로퍼티를 요구하는 common 로깅 autoconfig 로,
    // OpenAPI 스펙 생성과 무관하므로 제외한다(미제외 시 @PostConstruct validate 실패로 컨텍스트 로드 불가).
    @EnableAutoConfiguration(exclude = TcLogAutoConfiguration.class)
    @Import({OpenApiInfoConfig.class, AuthController.class, UiAuthCookieFactory.class})
    static class OpenApiTestApp {

        @Bean
        UiAuthProperties uiAuthProperties() {
            // 모든 인자를 기본값 유발 값으로 전달 → compact constructor 가 TC_UI_AUTH 등 기본값 적용.
            return new UiAuthProperties(0, 0, 0, 0, null, null, null, null, null, null, null, List.of());
        }

        @Bean
        LoginUseCase loginUseCase() {
            return mock(LoginUseCase.class);
        }

        @Bean
        LogoutUseCase logoutUseCase() {
            return mock(LogoutUseCase.class);
        }

        @Bean
        CsrfTokenRepository csrfTokenRepository() {
            return mock(CsrfTokenRepository.class);
        }
    }
}
