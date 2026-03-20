package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.deploy.DeployScenarioCatalogMeta;

import java.util.Optional;

/**
 * scenario catalog version/checksum 메타 저장 포트입니다.
 */
public interface DeployScenarioCatalogMetaPort {

    /**
     * 가장 최신 catalog 메타를 조회합니다.
     *
     * @return 최신 메타(Optional)
     */
    Optional<DeployScenarioCatalogMeta> findLatest();

    /**
     * 새 catalog 메타를 저장하고 저장된 행을 반환합니다.
     *
     * @param checksum catalog checksum
     * @param scenarioCount catalog에 포함된 scenario 수
     * @param loadedBy 반영 주체
     * @param reloadReason 반영 사유
     * @return 저장된 메타
     */
    DeployScenarioCatalogMeta append(String checksum, int scenarioCount, String loadedBy, String reloadReason);
}
