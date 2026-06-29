package com.nori.tc.ui.domain.audit;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 감사 로그 이벤트입니다. 누가(actor) 언제(occurredAt) 무엇을(action) 어느 대상에(target)
 * 어떤 결과로(outcome) 수행했는지를 기록합니다.
 *
 * <p>로그인 / 권한 변경 등 ui-backend 가 접수하는 중요 작업 발생 시 생성되어
 * {@code AuditLogPort} 를 통해 {@code tc_ui_audit_log} 에 적재됩니다.</p>
 *
 * @param actor      작업 주체 식별자(로그인 ID 등). 미상이면 "ANONYMOUS"/"SYSTEM"
 * @param action     작업 종류
 * @param target     작업 대상 식별자(계정 ID, "group:{id}/perm:{id}" 등)
 * @param outcome    작업 결과
 * @param occurredAt 작업 발생 시각
 * @param detail     부가 설명(실패 사유 등, 선택)
 */
public record UiAuditEvent(
        String actor,
        UiAuditAction action,
        String target,
        UiAuditOutcome outcome,
        OffsetDateTime occurredAt,
        String detail
) {

    public UiAuditEvent {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor는 필수입니다.");
        }
        Objects.requireNonNull(action, "action is null");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target은 필수입니다.");
        }
        Objects.requireNonNull(outcome, "outcome is null");
        Objects.requireNonNull(occurredAt, "occurredAt is null");
    }

    /**
     * 로그인 성공 이벤트를 생성합니다.
     *
     * @param actor 로그인한 사용자 ID
     * @return 로그인 성공 감사 이벤트
     */
    public static UiAuditEvent loginSuccess(final String actor) {
        return new UiAuditEvent(actor, UiAuditAction.LOGIN, actor, UiAuditOutcome.SUCCESS,
                OffsetDateTime.now(), null);
    }

    /**
     * 로그인 실패 이벤트를 생성합니다.
     *
     * @param actor  로그인 시도 ID
     * @param reason 실패 사유 코드
     * @return 로그인 실패 감사 이벤트
     */
    public static UiAuditEvent loginFailure(final String actor, final String reason) {
        return new UiAuditEvent(actor, UiAuditAction.LOGIN, actor, UiAuditOutcome.FAILURE,
                OffsetDateTime.now(), reason);
    }

    /**
     * 권한 부여 이벤트를 생성합니다.
     *
     * @param actor  부여를 수행한 사용자 ID
     * @param target 대상("group:{groupId}/perm:{permId}")
     * @return 권한 부여 감사 이벤트
     */
    public static UiAuditEvent permissionGrant(final String actor, final String target) {
        return new UiAuditEvent(actor, UiAuditAction.PERMISSION_GRANT, target, UiAuditOutcome.SUCCESS,
                OffsetDateTime.now(), null);
    }

    /**
     * 권한 회수 이벤트를 생성합니다.
     *
     * @param actor  회수를 수행한 사용자 ID
     * @param target 대상("group:{groupId}/perm:{permId}")
     * @return 권한 회수 감사 이벤트
     */
    public static UiAuditEvent permissionRevoke(final String actor, final String target) {
        return new UiAuditEvent(actor, UiAuditAction.PERMISSION_REVOKE, target, UiAuditOutcome.SUCCESS,
                OffsetDateTime.now(), null);
    }
}
