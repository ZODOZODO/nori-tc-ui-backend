package com.nori.tc.ui.adapters.web.dto.request;

/**
 * parent commit preview/실행 요청 DTO입니다.
 *
 * @param applyCommit true면 실제 commit을 수행하고, false 또는 null이면 diff preview만 수행합니다.
 * @param newParentVersion commit 시 생성할 새 parent version 문자열
 */
public record ModelParentCommitRequest(
        Boolean applyCommit,
        String newParentVersion
) {
}
