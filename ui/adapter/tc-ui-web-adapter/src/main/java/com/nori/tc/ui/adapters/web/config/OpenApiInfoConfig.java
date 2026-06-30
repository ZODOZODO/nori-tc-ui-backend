package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.core.properties.UiAuthProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 가 생성하는 OpenAPI 문서의 메타데이터와 보안 스킴을 커스터마이즈합니다.
 *
 * <p>starter 자동구성은 동작하지만 기본 title("OpenAPI definition")이 빈약하고,
 * 인증 방식(쿠키 토큰)을 securityScheme 으로 드러내지 못한다. 고객 인도용 API 레퍼런스가
 * 인증 경로를 명시하도록 이 빈에서 info + cookie securityScheme 을 채운다.</p>
 *
 * <p>실제 인증 쿠키 이름은 {@link UiAuthProperties#cookieName()} 단일 출처에서 읽어
 * 문서와 런타임 설정의 드리프트를 방지한다(기본값 {@code TC_UI_AUTH}).</p>
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiInfoConfig {

    private static final String COOKIE_AUTH_SCHEME = "cookieAuth";

    @Bean
    OpenAPI tcUiBackendOpenApi(final UiAuthProperties authProperties) {
        return new OpenAPI()
                .info(new Info()
                        .title("TC MES UI Backend API")
                        .version("v1")
                        .description(
                                "TC MES UI 백엔드 REST API. 인증은 로그인 시 발급되는 HttpOnly 쿠키"
                                        + "(`" + authProperties.cookieName() + "`)의 세션 토큰으로 처리되며, "
                                        + "endpoint 별 인가는 tc_user_group_permission 권한 모델을 단일 진실 원천으로 한다. "
                                        + "endpoint 와 권한 코드 매핑은 docs/api-reference/openapi.md 참조."))
                .components(new Components().addSecuritySchemes(
                        COOKIE_AUTH_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(authProperties.cookieName())
                                .description("로그인 응답으로 발급되는 HttpOnly 세션 토큰 쿠키")))
                .addSecurityItem(new SecurityRequirement().addList(COOKIE_AUTH_SCHEME));
    }
}
