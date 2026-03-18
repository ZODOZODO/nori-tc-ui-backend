package com.nori.tc.ui.adapters.web.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * UI Web Adapter 자동 구성 클래스입니다.
 *
 * <p>책임:</p>
 * <ul>
 *   <li>web-adapter 패키지(com.nori.tc.ui.adapters.web)만 선택적으로 스캔합니다.</li>
 *   <li>starter에서 광역 {@code com.nori.tc.ui} 스캔을 제거해도
 *       컨트롤러/보안 필터/웹 설정이 누락되지 않도록 보장합니다.</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.nori.tc.ui.adapters.web")
public class UiWebAdapterAutoConfiguration {
}
