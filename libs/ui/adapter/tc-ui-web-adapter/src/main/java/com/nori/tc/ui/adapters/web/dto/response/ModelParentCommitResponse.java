package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * model parent commit preview/실행 응답 DTO입니다.
 *
 * @param committed 실제 commit 수행 여부
 * @param branchModelKey branch model_key
 * @param parentModelKey parent model_key
 * @param branchModelName branch model_name
 * @param parentModelName parent model_name
 * @param branchLatestVersion branch 최신 version 문자열
 * @param parentLatestVersion parent 최신 version 문자열
 * @param newParentVersion 새 parent version 문자열
 * @param committedParentModelVersionKey commit 후 생성된 parent model_version_key
 * @param sections 상세 노드별 diff 결과
 */
public record ModelParentCommitResponse(
        boolean committed,
        long branchModelKey,
        long parentModelKey,
        String branchModelName,
        String parentModelName,
        String branchLatestVersion,
        String parentLatestVersion,
        String newParentVersion,
        Long committedParentModelVersionKey,
        List<ModelDiffSectionResponse> sections
) {
}
