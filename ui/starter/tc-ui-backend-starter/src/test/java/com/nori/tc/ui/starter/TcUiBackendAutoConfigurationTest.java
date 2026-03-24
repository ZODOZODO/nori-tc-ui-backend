package com.nori.tc.ui.starter;

import com.nori.tc.ui.core.auth.UiAuthAsyncExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class TcUiBackendAutoConfigurationTest {

    @Test
    @DisplayName("인증 전용 executor와 코어 executor 추상화를 생성한다")
    void 인증_전용_executor_생성() {
        final TcUiBackendAutoConfiguration configuration = new TcUiBackendAutoConfiguration();

        final ThreadPoolTaskExecutor taskExecutor = configuration.uiAuthTaskExecutor();
        final UiAuthAsyncExecutor authAsyncExecutor = configuration.uiAuthAsyncExecutor(taskExecutor);

        assertNotNull(taskExecutor);
        assertNotNull(authAsyncExecutor);
        taskExecutor.destroy();
    }
}
