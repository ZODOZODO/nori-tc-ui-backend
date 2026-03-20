package com.nori.tc.ui.core.deploy;

import java.util.List;

/**
 * deploy 계획을 DB에 저장한 결과입니다.
 */
public record PersistedDeploy(
        long deployKey,
        DeployStatus deployStatus,
        List<PersistedDeployTarget> targets
) {
}
