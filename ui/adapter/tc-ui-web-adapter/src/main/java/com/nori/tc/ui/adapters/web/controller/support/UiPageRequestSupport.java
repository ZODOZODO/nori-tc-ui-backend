package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.ui.core.exception.UiBadRequestException;

/**
 * UI 관리 API의 공통 페이징 입력 해석 유틸리티입니다.
 *
 * <p>목적:</p>
 * <ul>
 *   <li>컨트롤러별로 중복되기 쉬운 offset/limit 기본값 처리 로직을 단일화합니다.</li>
 *   <li>페이지 입력 정책(음수 금지, limit 상한)을 Web Adapter 계층에서 일관되게 강제합니다.</li>
 * </ul>
 *
 * <p>정책:</p>
 * <ul>
 *   <li>offset 기본값: 0</li>
 *   <li>limit 기본값: 100</li>
 *   <li>limit 상한: 500</li>
 * </ul>
 */
public final class UiPageRequestSupport {

    /**
     * 목록 API offset 기본값입니다.
     */
    public static final int DEFAULT_OFFSET = 0;

    /**
     * 목록 API limit 기본값입니다.
     */
    public static final int DEFAULT_LIMIT = 100;

    /**
     * 목록 API limit 상한값입니다.
     *
     * <p>과도한 단건 조회를 방지하여 DB와 애플리케이션 메모리 부담을 통제합니다.</p>
     */
    public static final int MAX_LIMIT = 500;

    /**
     * 유틸리티 클래스의 인스턴스 생성을 방지합니다.
     */
    private UiPageRequestSupport() {
        // 유틸리티 클래스는 정적 메서드만 사용합니다.
    }

    /**
     * 요청 파라미터를 {@link PageRequest}로 정규화합니다.
     *
     * @param offsetParam 요청 offset 값(없으면 null)
     * @param limitParam 요청 limit 값(없으면 null)
     * @return 검증 및 기본값 적용이 완료된 PageRequest
     * @throws UiBadRequestException offset/limit 정책 위반 시
     */
    public static PageRequest resolve(
            final Integer offsetParam,
            final Integer limitParam
    ) {
        final int offset = (offsetParam == null) ? DEFAULT_OFFSET : offsetParam;
        final int limit = (limitParam == null) ? DEFAULT_LIMIT : limitParam;

        if (offset < 0) {
            throw new UiBadRequestException("offset은 0 이상이어야 합니다.");
        }
        if (limit <= 0) {
            throw new UiBadRequestException("limit은 1 이상이어야 합니다.");
        }
        if (limit > MAX_LIMIT) {
            throw new UiBadRequestException("limit은 " + MAX_LIMIT + " 이하여야 합니다.");
        }

        return PageRequest.of(offset, limit);
    }
}

