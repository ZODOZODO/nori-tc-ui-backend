package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.application.admin.UserAdminFacade;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.UserPasswordResetRequest;
import com.nori.tc.ui.adapters.web.dto.request.UserUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMappingResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMembershipResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserListItemResponse;
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
 * 사용자 정보(User Info) 관리 REST API 컨트롤러입니다.
 *
 * <p>제공 엔드포인트:</p>
 * <ul>
 *   <li>GET /api/user</li>
 *   <li>GET /api/user/{userPk}</li>
 *   <li>POST /api/user</li>
 *   <li>PUT /api/user/{userPk}</li>
 *   <li>DELETE /api/user/{userPk}</li>
 *   <li>POST /api/user/{userPk}/group/{groupId}</li>
 *   <li>DELETE /api/user/{userPk}/group/{groupId}</li>
 *   <li>GET /api/user/{userPk}/group</li>
 *   <li>POST /api/user/{userPk}/password/reset</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserAdminFacade userAdminFacade;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param userAdminFacade 사용자 관리 facade
     */
    public UserController(
            final UserAdminFacade userAdminFacade
    ) {
        this.userAdminFacade = Objects.requireNonNull(userAdminFacade, "userAdminFacade is null");
    }

    /**
     * 사용자 목록을 페이지 단위로 조회합니다.
     *
     * @param offset 조회 시작 위치(기본값 0)
     * @param limit  조회 건수(기본값 100, 최대 500)
     * @return 목록 페이지 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<UserListItemResponse>>> list(
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final UiPageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        if (log.isDebugEnabled()) {
            log.debug("사용자 목록 조회 요청. offset={}, limit={}", pageRequest.offset(), pageRequest.limit());
        }

        final PagedResponse<UserListItemResponse> responsePage = userAdminFacade.list(pageRequest);

        if (log.isDebugEnabled()) {
            log.debug("사용자 목록 조회 완료. offset={}, limit={}, pageSize={}, totalCount={}",
                    responsePage.offset(), responsePage.limit(), responsePage.items().size(), responsePage.count());
        }

        return ResponseEntity.ok(ApiResponse.success(responsePage));
    }

    /**
     * 사용자 PK 기준으로 단건을 조회합니다.
     *
     * @param userPk 사용자 PK
     * @return 단건 조회 응답
     */
    @GetMapping("/{userPk}")
    public ResponseEntity<ApiResponse<UserInfoResponse>> get(
            @PathVariable final long userPk
    ) {
        if (log.isDebugEnabled()) {
            log.debug("사용자 단건 조회 요청. userPk={}", userPk);
        }

        final var optionalUser = userAdminFacade.findByUserPk(userPk);
        if (optionalUser.isEmpty()) {
            log.warn("사용자 단건 조회 결과 없음. userPk={}", userPk);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "사용자를 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(optionalUser.get()));
    }

    /**
     * 사용자를 등록합니다.
     *
     * @param request 등록 요청 본문
     * @return 등록된 사용자 정보
     */
    @PostMapping
    public ResponseEntity<ApiResponse<UserInfoResponse>> create(
            @Valid @RequestBody final UserUpsertRequest request
    ) {
        log.info("사용자 등록 요청. userId={}, email={}", request.userId(), request.email());

        final UserInfoResponse created = userAdminFacade.create(request);
        log.info("사용자 등록 완료. userPk={}, userIdNorm={}", created.userPk(), created.userIdNorm());
        return ResponseEntity.ok(ApiResponse.success(created));
    }

    /**
     * 사용자 정보를 수정합니다.
     *
     * @param userPk 수정 대상 사용자 PK
     * @param request 수정 요청 본문
     * @return 수정된 사용자 정보
     */
    @PutMapping("/{userPk}")
    public ResponseEntity<ApiResponse<UserInfoResponse>> update(
            @PathVariable final long userPk,
            @Valid @RequestBody final UserUpsertRequest request
    ) {
        log.info("사용자 수정 요청. userPk={}, userId={}, email={}", userPk, request.userId(), request.email());

        final var optionalExisting = userAdminFacade.update(userPk, request);
        if (optionalExisting.isEmpty()) {
            log.warn("사용자 수정 대상 없음. userPk={}", userPk);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "수정할 사용자를 찾을 수 없습니다."));
        }
        log.info("사용자 수정 완료. userPk={}, userIdNorm={}", optionalExisting.get().userPk(), optionalExisting.get().userIdNorm());
        return ResponseEntity.ok(ApiResponse.success(optionalExisting.get()));
    }

    /**
     * 사용자를 삭제합니다.
     *
     * @param userPk 삭제 대상 사용자 PK
     * @return 삭제 성공 응답
     */
    @DeleteMapping("/{userPk}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable final long userPk
    ) {
        log.info("사용자 삭제 요청. userPk={}", userPk);
        userAdminFacade.delete(userPk);
        log.info("사용자 삭제 완료. userPk={}", userPk);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 사용자-그룹 매핑을 생성합니다.
     *
     * @param userPk 사용자 PK
     * @param groupId 그룹 PK
     * @return 생성된 매핑 정보
     */
    @PostMapping("/{userPk}/group/{groupId}")
    public ResponseEntity<ApiResponse<UserGroupMappingResponse>> addUserGroupMapping(
            @PathVariable final long userPk,
            @PathVariable final long groupId
    ) {
        log.info("사용자-그룹 매핑 생성 요청. userPk={}, groupId={}", userPk, groupId);

        final UserGroupMappingResponse saved = userAdminFacade.addGroupMapping(userPk, groupId);

        log.info("사용자-그룹 매핑 생성 완료. ugmKey={}, userPk={}, groupId={}",
                saved.ugmKey(), saved.userPk(), saved.groupId());

        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    /**
     * 사용자-그룹 매핑을 삭제합니다.
     *
     * @param userPk 사용자 PK
     * @param groupId 그룹 PK
     * @return 삭제 성공 응답
     */
    @DeleteMapping("/{userPk}/group/{groupId}")
    public ResponseEntity<ApiResponse<Void>> removeUserGroupMapping(
            @PathVariable final long userPk,
            @PathVariable final long groupId
    ) {
        log.info("사용자-그룹 매핑 삭제 요청. userPk={}, groupId={}", userPk, groupId);
        userAdminFacade.removeGroupMapping(userPk, groupId);
        log.info("사용자-그룹 매핑 삭제 완료. userPk={}, groupId={}", userPk, groupId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 특정 사용자의 그룹 매핑 목록을 페이지 단위로 조회합니다.
     *
     * @param userPk 사용자 PK
     * @param offset 조회 시작 위치(기본값 0)
     * @param limit 조회 건수(기본값 100, 최대 500)
     * @return 사용자-그룹 매핑 페이지 응답
     */
    @GetMapping("/{userPk}/group")
    public ResponseEntity<ApiResponse<PagedResponse<UserGroupMembershipResponse>>> listGroupsByUser(
            @PathVariable final long userPk,
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final UiPageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        final var optionalPage = userAdminFacade.listGroupsByUserPk(userPk, pageRequest);
        if (optionalPage.isEmpty()) {
            log.warn("사용자-그룹 매핑 목록 조회 대상 사용자 없음. userPk={}", userPk);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "사용자를 찾을 수 없습니다."));
        }
        final PagedResponse<UserGroupMembershipResponse> responsePage = optionalPage.get();

        if (log.isDebugEnabled()) {
            log.debug("사용자-그룹 매핑 목록 조회 완료. userPk={}, offset={}, limit={}, pageSize={}, totalCount={}",
                    userPk, responsePage.offset(), responsePage.limit(), responsePage.items().size(), responsePage.count());
        }

        return ResponseEntity.ok(ApiResponse.success(responsePage));
    }

    /**
     * 사용자 비밀번호를 초기화합니다.
     *
     * @param userPk 초기화 대상 사용자 PK
     * @param request 비밀번호 초기화 요청
     * @return 초기화 성공 응답
     */
    @PostMapping("/{userPk}/password/reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @PathVariable final long userPk,
            @Valid @RequestBody final UserPasswordResetRequest request
    ) {
        log.info("사용자 비밀번호 초기화 요청. userPk={}", userPk);

        if (!userAdminFacade.resetPassword(userPk, request)) {
            log.warn("사용자 비밀번호 초기화 대상 없음. userPk={}", userPk);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "비밀번호를 초기화할 사용자를 찾을 수 없습니다."));
        }
        log.info("사용자 비밀번호 초기화 완료. userPk={}", userPk);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
