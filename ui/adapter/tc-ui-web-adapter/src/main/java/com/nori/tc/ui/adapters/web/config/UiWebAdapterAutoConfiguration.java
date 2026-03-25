package com.nori.tc.ui.adapters.web.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * UI Web Adapter 자동 구성 클래스입니다.
 *
 * <p>web-adapter 빈은 package scan이 아니라 module configuration import로만 등록합니다.</p>
 */
@AutoConfiguration
@Import(UiWebAdapterModuleConfiguration.class)
public class UiWebAdapterAutoConfiguration {
}
