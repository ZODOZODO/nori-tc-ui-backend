package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.domain.audit.UiAuditEvent;

/**
 * 감사 로그 적재 포트입니다.
 *
 * <p>로그인 / 권한 변경 등 중요 작업 발생 시 호출되어 {@code tc_ui_audit_log} 에 기록합니다.
 * 구현체는 <b>best-effort</b> 로 동작합니다 — 기록 실패가 본래 작업(로그인·권한변경)을
 * 중단시키지 않도록 예외를 전파하지 않고 ERROR 로그로 남깁니다.</p>
 */
public interface AuditLogPort {

    /**
     * 감사 이벤트를 기록합니다(best-effort, 예외 비전파).
     *
     * @param event 기록할 감사 이벤트
     */
    void record(UiAuditEvent event);
}
