package com.nori.tc.ui.domain.line;

/**
 * UI Backend 내부에서 사용하는 라인 스냅샷입니다.
 *
 * <p>tc_line 행을 UI 계층으로 전달하는 읽기 전용 ACL 모델입니다.</p>
 *
 * @param lineKey 라인 PK
 * @param lineCode 라인 코드(유니크)
 * @param lineName 라인 이름
 * @param isActive 활성 여부
 */
public record UiLine(
        long lineKey,
        String lineCode,
        String lineName,
        boolean isActive
) {
}
