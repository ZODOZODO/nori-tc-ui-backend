package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.domain.model.TcModel;

import java.util.List;

/**
 * branch model 생성/정리 기능을 담당하는 관리 포트입니다.
 *
 * <p>대상 엔드포인트:</p>
 * <ul>
 *   <li>{@code POST /api/model/{modelKey}/branches}</li>
 *   <li>{@code DELETE /api/model/{modelKey}/branches/deprecated}</li>
 * </ul>
 */
public interface ModelBranchCommandPort {

    /**
     * branch model 생성 명령입니다.
     *
     * @param parentModelKey 부모 root model의 model_key
     * @param suffix branch 이름에 사용할 사용자 입력 suffix
     * @param sourceModelVersionKey 복제 기준 root model_version_key (null이면 최신 버전)
     * @param currentUser 현재 로그인 사용자 ID
     */
    record CreateBranchModelCommand(
            long parentModelKey,
            String suffix,
            Long sourceModelVersionKey,
            String currentUser
    ) {
    }

    /**
     * deprecated branch 정리 결과입니다.
     *
     * @param deletedCount 삭제된 branch 개수
     * @param deletedModelKeys 삭제된 branch model_key 목록
     * @param deletedModelNames 삭제된 branch model_name 목록
     */
    record DeleteDeprecatedBranchesResult(
            int deletedCount,
            List<Long> deletedModelKeys,
            List<String> deletedModelNames
    ) {
    }

    /**
     * branch version checkout 명령입니다.
     *
     * @param sourceModelVersionKey EDIT로 복제할 기준 version key
     * @param currentUser 현재 로그인 사용자 ID
     */
    record CheckoutBranchVersionCommand(
            long sourceModelVersionKey,
            String currentUser
    ) {
    }

    /**
     * branch EDIT version checkin 명령입니다.
     *
     * @param editModelVersionKey 체크인할 EDIT version key
     * @param newVersion 새로 생성할 branch version
     * @param description 새 버전에 기록할 설명
     * @param currentUser 현재 로그인 사용자 ID
     */
    record CheckinBranchEditVersionCommand(
            long editModelVersionKey,
            String newVersion,
            String description,
            String currentUser
    ) {
    }

    /**
     * branch model을 생성합니다.
     *
     * @param command 생성 명령
     * @return 생성된 branch의 최신 모델 버전 스냅샷
     */
    TcModel createBranchModel(CreateBranchModelCommand command);

    /**
     * 선택한 branch version을 EDIT version으로 checkout합니다.
     *
     * <p>이미 동일 branch의 EDIT version이 있으면 소유권 정책을 검증한 뒤 그 version을 반환합니다.</p>
     *
     * @param command checkout 명령
     * @return 편집에 사용할 EDIT 모델 버전
     */
    TcModel checkoutBranchVersion(CheckoutBranchVersionCommand command);

    /**
     * EDIT version을 새 branch version으로 checkin합니다.
     *
     * @param command checkin 명령
     * @return 새로 생성된 branch 모델 버전
     */
    TcModel checkinBranchEditVersion(CheckinBranchEditVersionCommand command);

    /**
     * 선택된 root model에 연결된 deprecated branch를 일괄 삭제합니다.
     *
     * @param parentModelKey 부모 root model의 model_key
     * @return 삭제 결과
     */
    DeleteDeprecatedBranchesResult deleteDeprecatedBranches(long parentModelKey);
}
