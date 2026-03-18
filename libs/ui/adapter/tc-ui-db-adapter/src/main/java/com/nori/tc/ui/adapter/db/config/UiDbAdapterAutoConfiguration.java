package com.nori.tc.ui.adapter.db.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * UI DB Adapter 자동 구성 클래스입니다.
 *
 * <p>주의:</p>
 * <ul>
 *   <li>현재 DB 어댑터 구현 클래스는 legacy 패키지
 *       {@code com.nori.tc.ui.adapter.db} (단수) 아래에 위치합니다.</li>
 *   <li>향후 패키지 정리 전환을 고려해 {@code com.nori.tc.ui.adapters.db} (복수)도
 *       함께 스캔 대상으로 선언합니다.</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.nori.tc.ui.adapter.db",
        "com.nori.tc.ui.adapters.db"
})
public class UiDbAdapterAutoConfiguration {
}
