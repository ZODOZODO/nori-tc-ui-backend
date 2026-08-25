package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;

import java.util.List;
import java.util.Optional;

/**
 * 리소스 처리 장벽과 revision candidate 발급 포트입니다(§7.3/§18.1).
 */
public interface UiResourceHeadPort {

    /** {@link #issueCandidate} 결과 코드입니다. */
    enum IssueCode {
        /** 발급 성공. */
        OK,
        /** 다른 operation 이 같은 리소스를 claim 중입니다(§18.1 → 409). */
        RESOURCE_BUSY,
        /** 미종결 operation 이 리소스를 차단하고 있습니다(§13.8 REPAIR_REQUIRED 잔존). */
        RESOURCE_BLOCKED,
        /** expected base revision 이 현재 head 와 다릅니다. */
        REVISION_CONFLICT,
        /** 이 operation 이 이미 candidate 를 발급했습니다. */
        ALREADY_ISSUED
    }

    /**
     * candidate 발급 결과입니다.
     *
     * @param code 결과 코드
     * @param revision 발급/기존 revision(실패면 null)
     * @param conflictingOperationId 충돌 상대 operation(없으면 null)
     */
    record IssueResult(IssueCode code, Long revision, String conflictingOperationId) {
    }

    /**
     * 한 리소스의 candidate descriptor 입니다.
     *
     * @param resourceType 리소스 타입
     * @param resourceId 리소스 id
     * @param expectedBaseRevision 기대 base revision
     * @param descriptorJson candidate descriptor JSON
     */
    record Candidate(String resourceType, String resourceId, long expectedBaseRevision, String descriptorJson) {
    }

    /**
     * 전역 revision 을 발급하고 대상 리소스에 candidate 를 생성합니다.
     *
     * @param operationId candidate 소유 operation
     * @param candidates 대상 리소스(§18.1 lexicographic 정렬은 호출 측 책임)
     * @param nowEpochMs 현재 시각
     * @return 발급 결과
     */
    IssueResult issueCandidate(String operationId, List<Candidate> candidates, long nowEpochMs);

    /**
     * resource head 를 조회합니다.
     *
     * @param resourceType 리소스 타입
     * @param resourceId 리소스 id
     * @return head 스냅샷
     */
    UiResourceHeadView readHead(String resourceType, String resourceId);

    /**
     * revision descriptor JSON 을 조회합니다.
     *
     * @param resourceType 리소스 타입
     * @param resourceId 리소스 id
     * @param revision revision 번호
     * @return descriptor JSON
     */
    Optional<String> readDescriptor(String resourceType, String resourceId, long revision);

    /**
     * resourceKey 문자열을 resource head key 로 변환합니다.
     *
     * @param resourceKey {@code type:id} 형식 키
     * @return Redis resource head key
     */
    String headKeyOf(String resourceKey);
}
