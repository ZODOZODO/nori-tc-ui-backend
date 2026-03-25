package com.nori.tc.ui.adapters.kafka.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * UI Kafka Adapter 자동 구성 클래스입니다.
 *
 * <p>kafka-adapter 빈은 package scan이 아니라 module configuration import로만 등록합니다.</p>
 */
@AutoConfiguration
@Import(UiKafkaAdapterModuleConfiguration.class)
public class UiKafkaAdapterAutoConfiguration {
}
