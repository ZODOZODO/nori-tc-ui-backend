package com.nori.tc.ui.core.auth;

import java.util.concurrent.Executor;

/**
 * 인증/세션 비동기 작업 전용 executor 추상화입니다.
 *
 * <p>공용 풀을 직접 사용하지 않고, starter가 관리하는 전용 executor를 주입합니다.</p>
 */
@FunctionalInterface
public interface UiAuthAsyncExecutor extends Executor {

    static UiAuthAsyncExecutor direct() {
        return Runnable::run;
    }
}
