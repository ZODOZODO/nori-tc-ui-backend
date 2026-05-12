package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.application.admin.GroupAdminFacade;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.GroupUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.GroupInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.GroupMemberResponse;
import com.nori.tc.ui.adapters.web.dto.response.GroupPermissionMappingResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

import java.util.Objects;

/**
 * 그룹 정보(Group Info) 관리 REST API 컨트롤러입니다.
 *
 * <p>제공 엔드포인트:</p>
 * <ul>
 *   <li>GET /api/group</li>
 *   <li>GET /api/group/{groupId}</li>
 *   <li>POST /api/group</li>
 *   <li>PUT /api/group/{groupId}</li>
 *   <li>DELETE /api/group/{groupId}</li>
 *   <li>GET /api/group/{groupId}/permission</li>
 *   <li>POST /api/group/{groupId}/permission/{permId}</li>
 *   <li>DELETE /api/group/{groupId}/permission/{permId}</li>
 *   <li>GET /api/group/{groupId}/user</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/group")
public class GroupController {

    private static final Logger log = LoggerFactory.getLogger(GroupController.class);

    private final GroupAdminFacade groupAdminFacade;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param groupAdminFacade 그룹 관리 facade
     */
    public GroupController(
            final GroupAdminFacade groupAdminFacade
    ) {
        this.groupAdminFacade = Objects.requireNonNull(groupAdminFacade, "groupAdminFacade is null");
    }

    /**
     * 그룹 목록을 페이지 단위로 조회합니다.
     *
     * @param offset 조회 시작 위치(기본값 0)
     * @param limit 조회 건수(기본값 100, 최대 500)
     * @return 목록 페이지 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<GroupInfoResponse>>> list(
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final UiPageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        if (log.isDebugEnabled()) {
            log.debug("그룹 목록 조회 요청. offset={}, limit={}", pageRequest.offset(), pageRequest.limit());
        }

        final PagedResponse<GroupInfoResponse> responsePage = groupAdminFacade.list(pageRequest);

        if (log.isDebugEnabled()) {
            log.debug("그룹 목록 조회 완료. offset={}, limit={}, pageSize={}, totalCount={}",
                    responsePage.offset(), responsePage.limit(), responsePage.items().size(), responsePage.count());
        }

        return ResponseEntity.ok(ApiResponse.success(responsePage));
    }

    /**
     * 그룹 PK 기준으로 단건을 조회합니다.
     *
     * @param groupId 그룹 PK
     * @return 단건 조회 응답
     */
    @GetMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupInfoResponse>> get(
            @PathVariable final long groupId
    ) {
        if (log.isDebugEnabled()) {
            log.debug("그룹 단건 조회 요청. groupId={}", groupId);
        }

        final var optionalGroup = groupAdminFacade.findByGroupId(groupId);
        if (optionalGroup.isEmpty()) {
            log.warn("그룹 단건 조회 결과 없음. groupId={}", groupId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "그룹을 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(optionalGroup.get()));
    }

    /**
     * 그룹을 등록합니다.
     *
     * @param request 등록 요청 본문
     * @return 등록된 그룹 정보
     */
    @PostMapping
    public ResponseEntity<ApiResponse<GroupInfoResponse>> create(
            @Valid @RequestBody final GroupUpsertRequest request
    ) {
        log.info("그룹 등록 요청. groupCode={}, groupName={}", request.groupCode(), request.groupName());

        final GroupInfoResponse created = groupAdminFacade.create(request);
        log.info("그룹 등록 완료. groupId={}, groupCode={}", created.groupId(), created.groupCode());
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    /**
     * 그룹 정보를 수정합니다.
     *
     * @param groupId 수정 대상 그룹 PK
     * @param request 수정 요청 본문
     * @return 수정된 그룹 정보
     */
    @PutMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupInfoResponse>> update(
            @PathVariable final long groupId,
            @Valid @RequestBody final GroupUpsertRequest request
    ) {
        log.info("그룹 수정 요청. groupId={}, groupCode={}, groupName={}",
                groupId, request.groupCode(), request.groupName());

        final var optionalUpdated = groupAdminFacade.update(groupId, request);
        if (optionalUpdated.isEmpty()) {
            log.warn("그룹 수정 대상 없음. groupId={}", groupId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "수정할 그룹을 찾을 수 없습니다."));
        }
        log.info("그룹 수정 완료. groupId={}, groupCode={}", optionalUpdated.get().groupId(), optionalUpdated.get().groupCode());
        return ResponseEntity.ok(ApiResponse.success(optionalUpdated.get()));
    }

    /**
     * 그룹을 삭제합니다.
     *
     * @param groupId 삭제 대상 그룹 PK
     * @return 삭제 성공 응답
     */
    @DeleteMapping("/{groupId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable final long groupId
    ) {
        log.info("그룹 삭제 요청. groupId={}", groupId);
        groupAdminFacade.delete(groupId);
        log.info("그룹 삭제 완료. groupId={}", groupId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 특정 그룹의 권한 매핑 목록을 조회합니다.
     *
     * @param groupId 그룹 PK
     * @param offset 조회 시작 위치(기본값 0)
     * @param limit 조회 건수(기본값 100, 최대 500)
     * @return 그룹-권한 매핑 페이지 응답
     */
    @GetMapping("/{groupId}/permission")
    public ResponseEntity<ApiResponse<PagedResponse<GroupPermissionMappingResponse>>> listPermissions(
            @PathVariable final long groupId,
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final UiPageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        final var optionalPage = groupAdminFacade.listPermissions(groupId, pageRequest);
        if (optionalPage.isEmpty()) {
            log.warn("그룹-권한 매핑 목록 조회 대상 그룹 없음. groupId={}", groupId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "그룹을 찾을 수 없습니다."));
        }
        final PagedResponse<GroupPermissionMappingResponse> responsePage = optionalPage.get();

        if (log.isDebugEnabled()) {
            log.debug("그룹-권한 매핑 목록 조회 완료. groupId={}, offset={}, limit={}, pageSize={}, totalCount={}",
                    groupId, responsePage.offset(), responsePage.limit(), responsePage.items().size(), responsePage.count());
        }

        return ResponseEntity.ok(ApiResponse.success(responsePage));
    }

    /**
     * 그룹-권한 매핑을 생성합니다.
     *
     * @param groupId 그룹 PK
     * @param permId 권한 PK
     * @return 생성된 그룹-권한 매핑 정보
     */
    @PostMapping("/{groupId}/permission/{permId}")
    public ResponseEntity<ApiResponse<GroupPermissionMappingResponse>> addPermissionMapping(
            @PathVariable final long groupId,
            @PathVariable final long permId
    ) {
        log.info("그룹-권한 매핑 생성 요청. groupId={}, permId={}", groupId, permId);

        final GroupPermissionMappingResponse saved = groupAdminFacade.addPermissionMapping(groupId, permId);

        log.info("그룹-권한 매핑 생성 완료. ugpKey={}, groupId={}, permId={}",
                saved.ugpKey(), saved.groupId(), saved.permId());

        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    /**
     * 그룹-권한 매핑을 삭제합니다.
     *
     * @param groupId 그룹 PK
     * @param permId 권한 PK
     * @return 삭제 성공 응답
     */
    @DeleteMapping("/{groupId}/permission/{permId}")
    public ResponseEntity<ApiResponse<Void>> removePermissionMapping(
            @PathVariable final long groupId,
            @PathVariable final long permId
    ) {
        log.info("그룹-권한 매핑 삭제 요청. groupId={}, permId={}", groupId, permId);
        groupAdminFacade.removePermissionMapping(groupId, permId);
        log.info("그룹-권한 매핑 삭제 완료. groupId={}, permId={}", groupId, permId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 특정 그룹에 속한 사용자 목록을 페이지 단위로 조회합니다.
     *
     * @param groupId 그룹 PK
     * @param offset 조회 시작 위치(기본값 0)
     * @param limit 조회 건수(기본값 100, 최대 500)
     * @return 그룹 멤버 페이지 응답
     */
    @GetMapping("/{groupId}/user")
    public ResponseEntity<ApiResponse<PagedResponse<GroupMemberResponse>>> listUsersByGroup(
            @PathVariable final long groupId,
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final UiPageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        final var optionalPage = groupAdminFacade.listUsersByGroupId(groupId, pageRequest);
        if (optionalPage.isEmpty()) {
            log.warn("그룹 멤버 목록 조회 대상 그룹 없음. groupId={}", groupId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "그룹을 찾을 수 없습니다."));
        }
        final PagedResponse<GroupMemberResponse> responsePage = optionalPage.get();

        if (log.isDebugEnabled()) {
            log.debug("그룹 멤버 목록 조회 완료. groupId={}, offset={}, limit={}, pageSize={}, totalCount={}",
                    groupId, responsePage.offset(), responsePage.limit(), responsePage.items().size(), responsePage.count());
        }

        return ResponseEntity.ok(ApiResponse.success(responsePage));
    }
}
