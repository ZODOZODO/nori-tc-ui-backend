package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.request.LiveMapLayoutPutRequest;
import com.nori.tc.ui.adapters.web.dto.request.LiveMapObjectSaveRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.LiveMapLayoutResponse;
import com.nori.tc.ui.adapters.web.dto.response.LiveMapObjectResponse;
import com.nori.tc.ui.core.livemap.UpsertLiveMapPlacement;
import com.nori.tc.ui.core.service.LiveMapLayoutService;
import com.nori.tc.ui.core.service.LiveMapObjectService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * live-map 레이아웃 빌더 REST API 컨트롤러입니다(Phase 2, admin 저작).
 *
 * <p>모든 경로가 {@code /api/live-map/layout} prefix 아래에 있어 단일 write 권한
 * {@code LIVE_MAP_LAYOUT_WRITE}(PREFIX, 전 메서드)로 PUT/POST/DELETE 가 enforce 되고,
 * {@code LIVE_MAP_LAYOUT_READ}(PREFIX, GET 한정)로 GET 만 비-admin 에게 열립니다.
 * 권한 enforce 는 path 필터(UiApiPermissionCache)가 담당하므로 컨트롤러엔 별도 어노테이션이 없습니다.</p>
 */
@RestController
@RequestMapping("/api/live-map/layout")
public class LiveMapLayoutController {

    private final LiveMapLayoutService liveMapLayoutService;
    private final LiveMapObjectService liveMapObjectService;

    public LiveMapLayoutController(
            final LiveMapLayoutService liveMapLayoutService,
            final LiveMapObjectService liveMapObjectService
    ) {
        this.liveMapLayoutService = Objects.requireNonNull(liveMapLayoutService, "liveMapLayoutService is null");
        this.liveMapObjectService = Objects.requireNonNull(liveMapObjectService, "liveMapObjectService is null");
    }

    @GetMapping
    public ResponseEntity<ApiResponse<LiveMapLayoutResponse>> getLayout(
            @RequestParam final long lineKey
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                LiveMapLayoutResponse.from(liveMapLayoutService.getLayout(lineKey))
        ));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<LiveMapLayoutResponse>> putLayout(
            @RequestParam final long lineKey,
            @RequestBody final LiveMapLayoutPutRequest request
    ) {
        final List<UpsertLiveMapPlacement> placements = toPlacements(request);
        liveMapLayoutService.putLayout(lineKey, request == null ? null : request.canvasJson(), placements);
        return ResponseEntity.ok(ApiResponse.success(
                LiveMapLayoutResponse.from(liveMapLayoutService.getLayout(lineKey))
        ));
    }

    @GetMapping("/objects")
    public ResponseEntity<ApiResponse<List<LiveMapObjectResponse>>> listObjects() {
        return ResponseEntity.ok(ApiResponse.success(
                liveMapObjectService.listObjects().stream().map(LiveMapObjectResponse::from).toList()
        ));
    }

    @PostMapping("/objects")
    public ResponseEntity<ApiResponse<LiveMapObjectResponse>> createObject(
            @RequestBody final LiveMapObjectSaveRequest request
    ) {
        Objects.requireNonNull(request, "request is null");
        return ResponseEntity.ok(ApiResponse.success(LiveMapObjectResponse.from(
                liveMapObjectService.createObject(request.objectName(), request.objectKind(), request.shapeJson())
        )));
    }

    @PutMapping("/objects/{objectKey}")
    public ResponseEntity<ApiResponse<LiveMapObjectResponse>> updateObject(
            @PathVariable final long objectKey,
            @RequestBody final LiveMapObjectSaveRequest request
    ) {
        Objects.requireNonNull(request, "request is null");
        return ResponseEntity.ok(ApiResponse.success(LiveMapObjectResponse.from(
                liveMapObjectService.updateObject(objectKey, request.objectName(), request.objectKind(), request.shapeJson())
        )));
    }

    @DeleteMapping("/objects/{objectKey}")
    public ResponseEntity<ApiResponse<Void>> deleteObject(
            @PathVariable final long objectKey
    ) {
        liveMapObjectService.deleteObject(objectKey);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    private List<UpsertLiveMapPlacement> toPlacements(final LiveMapLayoutPutRequest request) {
        if (request == null || request.placements() == null) {
            return List.of();
        }
        return request.placements().stream()
                .map(placement -> new UpsertLiveMapPlacement(
                        placement.objectKey(),
                        placement.eqpKey(),
                        placement.transformJson()
                ))
                .toList();
    }
}
