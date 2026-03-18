package com.nori.tc.ui.core.port.db;

import java.util.List;

/**
 * branch model의 parent commit preview/반영을 담당하는 포트입니다.
 *
 * <p>대상 엔드포인트:</p>
 * <ul>
 *   <li>{@code POST /api/model/{modelKey}/commit-parent}</li>
 * </ul>
 */
public interface ModelParentCommitPort {

    /**
     * parent commit 요청 명령입니다.
     *
     * @param branchModelKey 대상 branch model의 model_key
     * @param applyCommit true면 diff 계산 후 실제 commit까지 수행
     * @param newParentVersion 새 parent version 문자열
     * @param currentUser 현재 로그인 사용자 ID
     */
    record CommitParentCommand(
            long branchModelKey,
            boolean applyCommit,
            String newParentVersion,
            String currentUser
    ) {
    }

    /**
     * diff 결과의 단일 항목입니다.
     *
     * @param identity 항목 식별자
     * @param branchValues branch 최신 버전 값
     * @param parentValues parent 최신 버전 값
     */
    record DiffItem(
            String identity,
            List<String> branchValues,
            List<String> parentValues
    ) {
    }

    /**
     * 상세 노드별 diff 섹션입니다.
     *
     * @param detailNode 상세 노드 식별자
     * @param columns 렌더링용 컬럼명 목록
     * @param added branch 기준 추가 항목
     * @param changed branch/parent 값이 다른 항목
     * @param deleted parent 기준 삭제 항목
     */
    record DiffSection(
            String detailNode,
            List<String> columns,
            List<DiffItem> added,
            List<DiffItem> changed,
            List<DiffItem> deleted
    ) {
    }

    /**
     * preview 또는 commit 처리 결과입니다.
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
    record CommitParentResult(
            boolean committed,
            long branchModelKey,
            long parentModelKey,
            String branchModelName,
            String parentModelName,
            String branchLatestVersion,
            String parentLatestVersion,
            String newParentVersion,
            Long committedParentModelVersionKey,
            List<DiffSection> sections
    ) {
    }

    /**
     * branch 최신 버전과 parent 최신 버전의 diff를 계산하고,
     * 요청 시 parent 새 버전에 commit까지 수행합니다.
     *
     * @param command preview/commit 명령
     * @return diff 결과와 commit 결과
     */
    CommitParentResult previewOrCommit(CommitParentCommand command);
}
