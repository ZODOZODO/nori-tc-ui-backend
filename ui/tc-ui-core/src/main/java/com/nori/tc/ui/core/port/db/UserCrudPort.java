package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.upsert.UpsertTcUserInfo;
import com.nori.tc.db.domain.user.TcUserInfo;
import com.nori.tc.ui.core.model.PagedResponse;

import java.util.Optional;

/**
 * 사용자 정보(User Info) CRUD 포트입니다.
 *
 * <p>UI 관리 페이지의 사용자 관리 기능이 이 포트를 통해 DB 계층과 통신합니다.</p>
 * <ul>
 *   <li>{@code GET /api/user}</li>
 *   <li>{@code GET /api/user/{userPk}}</li>
 *   <li>{@code POST /api/user}</li>
 *   <li>{@code PUT /api/user/{userPk}}</li>
 *   <li>{@code DELETE /api/user/{userPk}}</li>
 * </ul>
 *
 * <p>주의 사항:</p>
 * <p>인증 전용 조회(예: userIdNorm 기반 로그인 검증)는 기존 {@link UserPort}를 유지 사용합니다.</p>
 */
public interface UserCrudPort {

    /**
     * 사용자 정보를 업서트합니다.
     *
     * @param command 저장/수정 입력 명령
     * @return 저장 후 사용자 정보
     */
    TcUserInfo upsert(UpsertTcUserInfo command);

    /**
     * 사용자 PK 기준 단건을 조회합니다.
     *
     * @param userPk 사용자 기본 키
     * @return 조회 결과, 미존재 시 빈 Optional
     */
    Optional<TcUserInfo> findByUserPk(long userPk);

    /**
     * 사용자 목록을 페이지 단위로 조회합니다.
     *
     * @param pageRequest offset/limit 기반 페이지 요청
     * @return 페이지 응답(목록 + 전체 건수)
     */
    PagedResponse<TcUserInfo> findAll(PageRequest pageRequest);

    /**
     * 사용자 PK 기준으로 삭제합니다.
     *
     * @param userPk 삭제 대상 사용자 PK
     */
    void deleteByUserPk(long userPk);
}
