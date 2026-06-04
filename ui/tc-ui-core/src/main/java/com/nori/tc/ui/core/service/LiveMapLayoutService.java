package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.livemap.LiveMapLayoutView;
import com.nori.tc.ui.core.livemap.LiveMapObject;
import com.nori.tc.ui.core.livemap.LiveMapObjectKind;
import com.nori.tc.ui.core.livemap.LiveMapPlacementView;
import com.nori.tc.ui.core.livemap.UpsertLiveMapPlacement;
import com.nori.tc.ui.core.port.db.LiveMapLayoutStorePort;
import com.nori.tc.ui.core.port.db.LiveMapObjectStorePort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 라인 캔버스(tc_live_map_layout) + 배치(tc_live_map_placement) 조회/저장 서비스입니다.
 *
 * <p>write 권한은 path 필터가 enforce 하고, 본 서비스는 EQUIPMENT/STRUCTURE↔eqp_key 상호 배타와
 * 설비 존재·동일라인 소속을 서버 검증으로 강제합니다(SPEC (d) — DB CHECK 불가). FK 500 이 아닌 명시 400.</p>
 */
@Service
public class LiveMapLayoutService {

    private final LiveMapLayoutStorePort layoutStorePort;
    private final LiveMapObjectStorePort objectStorePort;

    public LiveMapLayoutService(
            final LiveMapLayoutStorePort layoutStorePort,
            final LiveMapObjectStorePort objectStorePort
    ) {
        this.layoutStorePort = Objects.requireNonNull(layoutStorePort, "layoutStorePort is null");
        this.objectStorePort = Objects.requireNonNull(objectStorePort, "objectStorePort is null");
    }

    /**
     * 라인의 캔버스 + 배치 + 참조 객체(중복 제거)를 조회합니다. 미저장 라인이면 빈 캔버스를 반환합니다.
     */
    public LiveMapLayoutView getLayout(final long lineKey) {
        requireLineKey(lineKey);

        final Optional<LiveMapLayoutView> found = layoutStorePort.findByLineKey(lineKey);
        if (found.isEmpty()) {
            return new LiveMapLayoutView(null, lineKey, null, List.of(), List.of());
        }

        final LiveMapLayoutView view = found.get();
        final List<Long> objectKeys = view.placements().stream()
                .map(LiveMapPlacementView::objectKey)
                .distinct()
                .toList();
        final List<LiveMapObject> objects = objectStorePort.findByKeyIn(objectKeys);
        return new LiveMapLayoutView(view.layoutKey(), view.lineKey(), view.canvasJson(), view.placements(), objects);
    }

    /**
     * 라인의 캔버스 메타 + 배치 전체를 저장합니다(검증 후 단일 트랜잭션 replace).
     *
     * @return 저장된 layout_key
     */
    public long putLayout(final long lineKey, final JsonNode canvasJson, final List<UpsertLiveMapPlacement> placements) {
        requireLineKey(lineKey);
        if (canvasJson != null && !canvasJson.isNull()) {
            LiveMapSchemaSupport.requireSchemaVersion(canvasJson, "canvasJson");
        }

        final List<UpsertLiveMapPlacement> safePlacements = placements == null ? List.of() : placements;
        for (final UpsertLiveMapPlacement placement : safePlacements) {
            validatePlacement(lineKey, placement);
        }
        return layoutStorePort.saveLayout(lineKey, canvasJson, safePlacements);
    }

    private void validatePlacement(final long lineKey, final UpsertLiveMapPlacement placement) {
        if (placement == null) {
            throw new UiBadRequestException("placement 항목이 비어 있습니다.");
        }
        if (placement.objectKey() <= 0L) {
            throw new UiBadRequestException("placement.objectKey는 1 이상이어야 합니다.");
        }
        LiveMapSchemaSupport.requireSchemaVersion(placement.transformJson(), "transformJson");

        final LiveMapObject object = objectStorePort.findByKey(placement.objectKey())
                .orElseThrow(() -> new UiBadRequestException(
                        "존재하지 않는 객체입니다. objectKey=" + placement.objectKey()));

        if (object.objectKind() == LiveMapObjectKind.EQUIPMENT) {
            if (placement.eqpKey() == null) {
                throw new UiBadRequestException(
                        "EQUIPMENT 객체 배치는 eqpKey가 필수입니다. objectKey=" + placement.objectKey());
            }
        } else if (placement.eqpKey() != null) {
            throw new UiBadRequestException(
                    "STRUCTURE 객체 배치는 eqpKey를 가질 수 없습니다. objectKey=" + placement.objectKey());
        }

        if (placement.eqpKey() != null) {
            final long eqpKey = placement.eqpKey();
            if (eqpKey <= 0L) {
                throw new UiBadRequestException("eqpKey는 1 이상이어야 합니다.");
            }
            final Long eqpLineKey = layoutStorePort.findEqpLineKey(eqpKey)
                    .orElseThrow(() -> new UiBadRequestException("존재하지 않는 설비입니다. eqpKey=" + eqpKey));
            if (eqpLineKey.longValue() != lineKey) {
                throw new UiBadRequestException(
                        "설비가 해당 라인 소속이 아닙니다. eqpKey=" + eqpKey + ", lineKey=" + lineKey);
            }
        }
    }

    private void requireLineKey(final long lineKey) {
        if (lineKey <= 0L) {
            throw new UiBadRequestException("lineKey는 1 이상이어야 합니다.");
        }
    }
}
