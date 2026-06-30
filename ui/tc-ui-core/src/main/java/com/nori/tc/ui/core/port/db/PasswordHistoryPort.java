package com.nori.tc.ui.core.port.db;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 비밀번호 이력(tc_user_password_history) DB 접근 포트입니다.
 *
 * <p>역할:</p>
 * <p>비밀번호 재사용 금지(직전 N회) 검사를 위해 최근 비밀번호 해시를 조회하고,
 * 변경 성공 시 새 해시를 이력에 적재합니다.</p>
 *
 * <p>구현체:</p>
 * <p>tc-ui-db-adapter의 JdbcPasswordHistoryPort가 이 인터페이스를 구현합니다.</p>
 */
public interface PasswordHistoryPort {

    /**
     * 사용자의 최근 비밀번호 해시를 변경 시각 최신순으로 조회합니다.
     *
     * @param userPk 사용자 PK
     * @param limit  조회할 최대 이력 개수(재사용 금지 윈도우 크기)
     * @return 최신순 비밀번호 해시 목록(이력 없으면 빈 목록)
     */
    List<String> recentHashes(long userPk, int limit);

    /**
     * 비밀번호 변경 이력을 적재합니다.
     *
     * @param userPk       사용자 PK
     * @param passwordHash 새로 적용된 비밀번호 해시
     * @param changedAt    변경 시각
     */
    void record(long userPk, String passwordHash, OffsetDateTime changedAt);
}
