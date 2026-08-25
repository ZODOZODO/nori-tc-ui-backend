package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiMembershipState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * readiness 와 관측에 노출하는 조정 상태 표면입니다(§13.11).
 */
public interface UiCoordinationStatus {

    /**
     * 조정 원장 경로가 활성인지 반환합니다.
     *
     * @return 활성이면 true
     */
    boolean coordinationEnabled();

    /**
     * 현재 membership 상태를 반환합니다.
     *
     * @return membership 상태
     */
    UiMembershipState membershipState();

    /**
     * mutation 을 받아들일 수 있는 상태인지 반환합니다.
     *
     * @return 가능하면 true
     */
    boolean readyForMutation();

    /**
     * readiness detail 로 노출할 값을 반환합니다.
     *
     * @return detail map
     */
    Map<String, Object> details();

    /**
     * 조정을 쓰지 않는 구성용 상태를 반환합니다.
     *
     * @return 항상 준비된 것으로 보고하는 상태
     */
    static UiCoordinationStatus disabled() {
        return new UiCoordinationStatus() {
            @Override
            public boolean coordinationEnabled() {
                return false;
            }

            @Override
            public UiMembershipState membershipState() {
                return UiMembershipState.ACTIVE_SYNCED;
            }

            @Override
            public boolean readyForMutation() {
                return true;
            }

            @Override
            public Map<String, Object> details() {
                final Map<String, Object> details = new LinkedHashMap<>();
                details.put("coordinationEnabled", false);
                return details;
            }
        };
    }
}
