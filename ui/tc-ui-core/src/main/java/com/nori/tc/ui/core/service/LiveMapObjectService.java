package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.livemap.LiveMapObject;
import com.nori.tc.ui.core.livemap.LiveMapObjectKind;
import com.nori.tc.ui.core.port.db.LiveMapObjectStorePort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * live-map 재사용 합성 객체(tc_live_map_object) CRUD 서비스입니다.
 *
 * <p>write 권한(LIVE_MAP_LAYOUT_WRITE)은 path 필터(UiApiPermissionCache)가 enforce 하므로
 * 본 서비스는 비즈니스 입력 검증(이름/종류/스키마 버전)만 담당합니다.</p>
 */
@Service
public class LiveMapObjectService {

    private final LiveMapObjectStorePort objectStorePort;

    public LiveMapObjectService(final LiveMapObjectStorePort objectStorePort) {
        this.objectStorePort = Objects.requireNonNull(objectStorePort, "objectStorePort is null");
    }

    public List<LiveMapObject> listObjects() {
        return objectStorePort.findAllActive();
    }

    public LiveMapObject createObject(final String objectName, final String objectKind, final JsonNode shapeJson) {
        final String name = requireName(objectName);
        final LiveMapObjectKind kind = parseKind(objectKind);
        LiveMapSchemaSupport.requireSchemaVersion(shapeJson, "shapeJson");

        final long objectKey = objectStorePort.save(name, kind, shapeJson);
        return objectStorePort.findByKey(objectKey)
                .orElseThrow(() -> new UiBadRequestException("객체 저장 후 조회에 실패했습니다. objectKey=" + objectKey));
    }

    public LiveMapObject updateObject(
            final long objectKey,
            final String objectName,
            final String objectKind,
            final JsonNode shapeJson
    ) {
        requirePositiveKey(objectKey);
        final String name = requireName(objectName);
        final LiveMapObjectKind kind = parseKind(objectKind);
        LiveMapSchemaSupport.requireSchemaVersion(shapeJson, "shapeJson");

        if (!objectStorePort.update(objectKey, name, kind, shapeJson)) {
            throw new UiBadRequestException("존재하지 않는 객체입니다. objectKey=" + objectKey);
        }
        return objectStorePort.findByKey(objectKey)
                .orElseThrow(() -> new UiBadRequestException("객체 수정 후 조회에 실패했습니다. objectKey=" + objectKey));
    }

    public void deleteObject(final long objectKey) {
        requirePositiveKey(objectKey);
        if (!objectStorePort.delete(objectKey)) {
            throw new UiBadRequestException("존재하지 않는 객체입니다. objectKey=" + objectKey);
        }
    }

    private String requireName(final String objectName) {
        if (objectName == null || objectName.isBlank()) {
            throw new UiBadRequestException("objectName은 필수입니다.");
        }
        return objectName.trim();
    }

    private LiveMapObjectKind parseKind(final String objectKind) {
        try {
            return LiveMapObjectKind.fromText(objectKind);
        } catch (IllegalArgumentException exception) {
            throw new UiBadRequestException(exception.getMessage());
        }
    }

    private void requirePositiveKey(final long objectKey) {
        if (objectKey <= 0L) {
            throw new UiBadRequestException("objectKey는 1 이상이어야 합니다.");
        }
    }
}
