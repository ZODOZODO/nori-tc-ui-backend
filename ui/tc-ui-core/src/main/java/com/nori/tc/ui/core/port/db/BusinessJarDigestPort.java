package com.nori.tc.ui.core.port.db;

import java.util.Optional;

/**
 * Business plugin JAR 아티팩트의 locator(파일명 + SHA-256)를 조회하는 포트입니다(§15.2).
 *
 * <p>digest 는 저장하지 않고 <b>저장된 바이트에서 매번 계산</b>합니다 — 컬럼으로 두면 JAR row 가
 * 갱신될 때 조용히 어긋날 수 있고, 어긋난 digest 는 Business 의 아티팩트 검증(§11.4)을 잘못된
 * 근거로 통과시킵니다. 계산은 어댑터에서 수행해 JAR 바이트가 core 로 올라오지 않게 합니다.</p>
 */
public interface BusinessJarDigestPort {

    /**
     * manifest 에 실을 JAR locator 입니다.
     *
     * <p>파일명과 digest 는 <b>같은 row 에서</b> 함께 읽습니다. 따로 읽으면 한쪽이 다른 JAR 의 값이
     * 되어 Business 의 locator 검증이 어긋납니다.</p>
     *
     * @param jarFileName JAR 파일명
     * @param sha256Hex JAR 바이트의 SHA-256 소문자 hex(바이트가 없으면 null)
     */
    record JarLocator(String jarFileName, String sha256Hex) {
    }

    /**
     * business jar 의 locator 를 조회합니다.
     *
     * @param businessJarKey business jar 키
     * @return locator(해당 JAR 이 없으면 empty)
     */
    Optional<JarLocator> findLocator(long businessJarKey);
}
