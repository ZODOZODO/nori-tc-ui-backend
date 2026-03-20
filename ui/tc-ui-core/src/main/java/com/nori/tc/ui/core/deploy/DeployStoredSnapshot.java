package com.nori.tc.ui.core.deploy;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/**
 * DB에 저장된 deploy target snapshot 1건입니다.
 */
public record DeployStoredSnapshot(
        long deployTargetSnapshotKey,
        long deployTargetKey,
        DeploySnapshotScope snapshotScope,
        String stepCode,
        DeploySnapshotKind snapshotKind,
        String sourceOfTruth,
        JsonNode snapshotData,
        OffsetDateTime capturedAt
) {
}
