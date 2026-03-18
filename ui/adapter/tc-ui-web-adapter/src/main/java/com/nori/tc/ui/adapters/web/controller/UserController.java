package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroupMember;
import com.nori.tc.db.core.user.upsert.UpsertTcUserInfo;
import com.nori.tc.db.domain.common.user.UserStatus;
import com.nori.tc.db.domain.user.TcUserGroupMember;
import com.nori.tc.db.domain.user.TcUserInfo;
import com.nori.tc.ui.adapters.web.controller.support.UiPageRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.UserPasswordResetRequest;
import com.nori.tc.ui.adapters.web.dto.request.UserUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMappingResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserInfoResponse;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.UserCrudPort;
import com.nori.tc.ui.core.port.db.UserGroupMappingPort;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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
 *   <li>POST /api/user/{userPk}/password/reset</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    /**
     * 사용자-그룹 매핑 생성 시 기본 부여자를 남기기 위한 상수입니다.
     */
    private static final String DEFAULT_GRANTED_BY = "SYSTEM";

    /**
     * BCrypt 해시 인코더입니다.
     *
     * <p>비밀번호 평문은 절대 저장하지 않고, 저장 직전에 반드시 BCrypt 해시로 변환합니다.</p>
     */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private final UserCrudPort userCrudPort;
    private final UserGroupMappingPort userGroupMappingPort;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param userCrudPort 사용자 CRUD 포트
     * @param userGroupMappingPort 사용자-그룹 매핑 포트
     */
    public UserController(
            final UserCrudPort userCrudPort,
            final UserGroupMappingPort userGroupMappingPort
    ) {
        this.userCrudPort = Objects.requireNonNull(userCrudPort, "userCrudPort is null");
        this.userGroupMappingPort = Objects.requireNonNull(userGroupMappingPort, "userGroupMappingPort is null");
    }

    /**
     * 사용자 목록을 페이지 단위로 조회합니다.
     *
     * @param offset 조회 시작 위치(기본값 0)
     * @param limit  조회 건수(기본값 100, 최대 500)
     * @return 목록 페이지 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<UserInfoResponse>>> list(
            @RequestParam(name = "offset", required = false) final Integer offset,
            @RequestParam(name = "limit", required = false) final Integer limit
    ) {
        final PageRequest pageRequest = UiPageRequestSupport.resolve(offset, limit);

        if (log.isDebugEnabled()) {
            log.debug("사용자 목록 조회 요청. offset={}, limit={}", pageRequest.offset(), pageRequest.limit());
        }

        final PagedResponse<TcUserInfo> page = userCrudPort.findAll(pageRequest);
        final PagedResponse<UserInfoResponse> responsePage = toUserPage(page);

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

        final Optional<TcUserInfo> optionalUser = userCrudPort.findByUserPk(userPk);
        if (optionalUser.isEmpty()) {
            log.warn("사용자 단건 조회 결과 없음. userPk={}", userPk);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "사용자를 찾을 수 없습니다."));
        }

        return ResponseEntity.ok(ApiResponse.success(toUserInfoResponse(optionalUser.get())));
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

        final String rawPassword = resolveCreatePassword(request.password());
        final UpsertTcUserInfo command = buildUserUpsertCommand(
                null,
                request,
                PASSWORD_ENCODER.encode(rawPassword),
                resolveStatusForCreate(request.status())
        );

        final TcUserInfo created = userCrudPort.upsert(command);
        log.info("사용자 등록 완료. userPk={}, userIdNorm={}", created.userPk(), created.userIdNorm());

        return ResponseEntity.ok(ApiResponse.success(toUserInfoResponse(created)));
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

        final Optional<TcUserInfo> optionalExisting = userCrudPort.findByUserPk(userPk);
        if (optionalExisting.isEmpty()) {
            log.warn("사용자 수정 대상 없음. userPk={}", userPk);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "수정할 사용자를 찾을 수 없습니다."));
        }

        final TcUserInfo existing = optionalExisting.get();
        final String passwordHash = hasText(request.password())
                ? PASSWORD_ENCODER.encode(request.password().trim())
                : existing.passwordHash();
        final UserStatus status = (request.status() == null) ? existing.status() : request.status();

        final UpsertTcUserInfo command = buildUserUpsertCommand(
                userPk,
                request,
                passwordHash,
                status
        );

        final TcUserInfo updated = userCrudPort.upsert(command);
        log.info("사용자 수정 완료. userPk={}, userIdNorm={}", updated.userPk(), updated.userIdNorm());

        return ResponseEntity.ok(ApiResponse.success(toUserInfoResponse(updated)));
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
        userCrudPort.deleteByUserPk(userPk);
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

        final UpsertTcUserGroupMember command = new UpsertTcUserGroupMember(
                null,
                userPk,
                groupId,
                null,
                DEFAULT_GRANTED_BY
        );
        final TcUserGroupMember saved = userGroupMappingPort.upsert(command);

        log.info("사용자-그룹 매핑 생성 완료. ugmKey={}, userPk={}, groupId={}",
                saved.ugmKey(), saved.userPk(), saved.groupId());

        return ResponseEntity.ok(ApiResponse.success(toUserGroupMappingResponse(saved)));
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
        userGroupMappingPort.deleteByUserPkAndGroupId(userPk, groupId);
        log.info("사용자-그룹 매핑 삭제 완료. userPk={}, groupId={}", userPk, groupId);
        return ResponseEntity.ok(ApiResponse.success(null));
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

        final Optional<TcUserInfo> optionalExisting = userCrudPort.findByUserPk(userPk);
        if (optionalExisting.isEmpty()) {
            log.warn("사용자 비밀번호 초기화 대상 없음. userPk={}", userPk);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "비밀번호를 초기화할 사용자를 찾을 수 없습니다."));
        }

        final TcUserInfo existing = optionalExisting.get();
        final String passwordHash = PASSWORD_ENCODER.encode(request.newPassword().trim());

        final UpsertTcUserInfo command = new UpsertTcUserInfo(
                existing.userPk(),
                existing.company(),
                existing.department(),
                existing.userName(),
                existing.userId(),
                existing.userIdNorm(),
                passwordHash,
                existing.email(),
                existing.status(),
                existing.createdBy(),
                request.updatedBy()
        );

        userCrudPort.upsert(command);
        log.info("사용자 비밀번호 초기화 완료. userPk={}", userPk);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 사용자 upsert 명령을 생성합니다.
     *
     * @param userPk 사용자 PK(신규 생성 시 null)
     * @param request 원본 요청
     * @param passwordHash 저장할 BCrypt 해시
     * @param status 저장할 사용자 상태
     * @return upsert 명령
     */
    private static UpsertTcUserInfo buildUserUpsertCommand(
            final Long userPk,
            final UserUpsertRequest request,
            final String passwordHash,
            final UserStatus status
    ) {
        final String normalizedUserId = normalizeUserId(request.userId());
        final String userIdNorm = normalizeUserIdNorm(normalizedUserId);

        return new UpsertTcUserInfo(
                userPk,
                request.company(),
                request.department(),
                request.userName(),
                normalizedUserId,
                userIdNorm,
                passwordHash,
                request.email(),
                status,
                request.createdBy(),
                request.updatedBy()
        );
    }

    /**
     * 신규 생성 시 비밀번호 입력값을 검증하고 반환합니다.
     *
     * @param rawPassword 요청 비밀번호
     * @return trim 처리된 비밀번호
     * @throws UiBadRequestException 비밀번호 누락 시
     */
    private static String resolveCreatePassword(final String rawPassword) {
        if (!hasText(rawPassword)) {
            throw new UiBadRequestException("사용자 생성 시 password는 필수입니다.");
        }
        return rawPassword.trim();
    }

    /**
     * 사용자 생성 시 상태 기본값을 결정합니다.
     *
     * @param status 요청 상태
     * @return null이면 ACTIVE, 아니면 요청값
     */
    private static UserStatus resolveStatusForCreate(final UserStatus status) {
        return (status == null) ? UserStatus.ACTIVE : status;
    }

    /**
     * 사용자 ID를 trim 기반으로 정규화합니다.
     *
     * @param userId 원본 사용자 ID
     * @return 앞뒤 공백 제거된 사용자 ID
     */
    private static String normalizeUserId(final String userId) {
        if (!hasText(userId)) {
            throw new UiBadRequestException("userId는 비어 있을 수 없습니다.");
        }
        return userId.trim();
    }

    /**
     * 로그인 조회 키(user_id_norm)를 생성합니다.
     *
     * @param normalizedUserId trim 처리된 userId
     * @return 소문자 변환된 userIdNorm
     */
    private static String normalizeUserIdNorm(final String normalizedUserId) {
        return normalizedUserId.toLowerCase(Locale.ROOT);
    }

    /**
     * null/blank 여부를 간단히 판별합니다.
     *
     * @param text 검사 대상 문자열
     * @return 비어 있지 않으면 true
     */
    private static boolean hasText(final String text) {
        return text != null && !text.trim().isEmpty();
    }

    /**
     * 도메인 페이지 응답을 API 응답 DTO 페이지로 변환합니다.
     *
     * @param page 도메인 페이지 응답
     * @return API 응답 페이지
     */
    private static PagedResponse<UserInfoResponse> toUserPage(final PagedResponse<TcUserInfo> page) {
        final List<UserInfoResponse> items = page.items().stream()
                .map(UserController::toUserInfoResponse)
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    /**
     * 사용자 도메인을 응답 DTO로 변환합니다.
     *
     * @param user 도메인 사용자 객체
     * @return 응답 DTO
     */
    private static UserInfoResponse toUserInfoResponse(final TcUserInfo user) {
        return new UserInfoResponse(
                user.userPk(),
                user.company(),
                user.department(),
                user.userName(),
                user.userId(),
                user.userIdNorm(),
                user.email(),
                user.status(),
                user.createdAt(),
                user.updatedAt(),
                user.createdBy(),
                user.updatedBy()
        );
    }

    /**
     * 사용자-그룹 매핑 도메인을 응답 DTO로 변환합니다.
     *
     * @param mapping 도메인 매핑 객체
     * @return 응답 DTO
     */
    private static UserGroupMappingResponse toUserGroupMappingResponse(final TcUserGroupMember mapping) {
        return new UserGroupMappingResponse(
                mapping.ugmKey(),
                mapping.userPk(),
                mapping.groupId(),
                mapping.grantedAt(),
                mapping.grantedBy()
        );
    }
}

