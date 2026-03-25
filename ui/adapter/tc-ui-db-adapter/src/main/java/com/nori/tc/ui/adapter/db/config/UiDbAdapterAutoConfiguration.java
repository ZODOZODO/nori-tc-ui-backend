package com.nori.tc.ui.adapter.db.config;

import com.nori.tc.ui.adapter.db.UiDbAdapterModuleConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * UI DB Adapter 자동 구성 클래스입니다.
 *
 * <p>legacy 패키지(`com.nori.tc.ui.adapter.db`)와 신규 패키지(`com.nori.tc.ui.adapters.db`)에
 * 흩어진 구현도 모두 명시적 import 목록으로 고정합니다.</p>
 */
@AutoConfiguration
@Import(UiDbAdapterModuleConfiguration.class)
public class UiDbAdapterAutoConfiguration {
}
