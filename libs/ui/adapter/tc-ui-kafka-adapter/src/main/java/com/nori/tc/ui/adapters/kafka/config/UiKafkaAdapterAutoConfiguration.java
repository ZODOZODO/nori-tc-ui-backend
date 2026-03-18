package com.nori.tc.ui.adapters.kafka.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * UI Kafka Adapter 자동 구성 클래스입니다.
 *
 * <p>책임:</p>
 * <ul>
 *   <li>kafka-adapter 패키지(com.nori.tc.ui.adapters.kafka)만 스캔해
 *       발행/구독 어댑터와 Kafka 설정 빈을 명시적으로 등록합니다.</li>
 *   <li>starter의 컴포넌트 스캔 범위를 최소화(core 한정)해도
 *       Kafka 관련 빈이 누락되지 않도록 보장합니다.</li>
 * </ul>
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.nori.tc.ui.adapters.kafka")
public class UiKafkaAdapterAutoConfiguration {
}
