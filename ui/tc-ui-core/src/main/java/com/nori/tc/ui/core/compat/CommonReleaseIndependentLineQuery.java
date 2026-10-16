package com.nori.tc.ui.core.compat;

import java.util.List;
import java.util.Objects;

import com.nori.tc.ui.core.service.LineQueryService;
import com.nori.tc.ui.domain.line.UiLine;

/**
 * 서비스 저장소만 바꿔 UI 조회를 추가할 수 있음을 검증하는 고정 fixture 입니다(SPEC §10.1, COM-008·COM-036).
 *
 * <p>목적: 기존 서비스 공개 API {@link LineQueryService} 만 사용하는 조회 class 를 이 저장소에 추가해도
 * common·WM module 과 공통 version 을 바꾸거나 다시 발행하지 않고 build·test 가 끝나는지 확인합니다.</p>
 *
 * <p>제약: Spring bean·controller 로 등록하지 않으며(annotation 없음) 조회 결과를 그대로 반환합니다.
 * 분기가 없어 대응 test 가 모든 줄을 실행합니다.</p>
 */
public final class CommonReleaseIndependentLineQuery {

    private final LineQueryService lineQueryService;

    /**
     * 기존 라인 조회 서비스를 주입받습니다.
     *
     * @param lineQueryService 라인 조회 서비스(null 금지)
     */
    public CommonReleaseIndependentLineQuery(final LineQueryService lineQueryService) {
        this.lineQueryService = Objects.requireNonNull(lineQueryService, "lineQueryService is null");
    }

    /**
     * 활성 라인 목록을 서비스가 반환한 값·순서 그대로 돌려줍니다.
     *
     * @return 활성 라인 목록
     */
    public List<UiLine> activeLines() {
        return lineQueryService.getActiveLines();
    }
}
