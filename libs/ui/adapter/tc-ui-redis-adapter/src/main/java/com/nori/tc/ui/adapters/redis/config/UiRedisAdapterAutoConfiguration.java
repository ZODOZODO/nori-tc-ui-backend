package com.nori.tc.ui.adapters.redis.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * UI Redis Adapter 자동 구성 클래스입니다.
 *
 * <p>책임:</p>
 * <ul>
 *   <li>redis-adapter 패키지(com.nori.tc.ui.adapters.redis)만 스캔해
 *       이중 Redis 연결/세션 캐시/비동기 결과 저장 빈을 등록합니다.</li>
 *   <li>starter에서 전역 스캔을 제거하더라도 Redis 관련 어댑터 빈이
 *       누락되지 않게 명시 조립 지점을 제공합니다.</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.nori.tc.ui.adapters.redis")
public class UiRedisAdapterAutoConfiguration {
}
