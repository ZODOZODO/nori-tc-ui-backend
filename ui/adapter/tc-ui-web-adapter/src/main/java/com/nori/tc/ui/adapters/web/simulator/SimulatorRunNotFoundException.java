package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.core.exception.UiNotFoundException;

/**
 * simulator orchestrator 가 해당 runId 의 Run 을 더는 보유하지 않을 때(HTTP 404) 던지는 전용 신호.
 *
 * <p>reaper 가 일시 오류(5xx/IO)와 "종료로 간주할 404"를 구분해 catch 할 수 있도록 distinct 타입으로 둔다.
 * {@link UiNotFoundException} 을 상속하므로 폴링/미추적-stop 경로로 전파돼도 web 예외 핸들러가 404 로 매핑한다
 * (미분류 RuntimeException → 500 회귀 회피).</p>
 */
public class SimulatorRunNotFoundException extends UiNotFoundException {

    public SimulatorRunNotFoundException(final String message) {
        super(message);
    }
}
