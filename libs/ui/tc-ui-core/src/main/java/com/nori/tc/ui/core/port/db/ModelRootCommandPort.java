package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.model.TcModel;

/**
 * root model 생성/수정/삭제를 담당하는 관리 포트입니다.
 *
 * <p>대상 엔드포인트:</p>
 * <ul>
 *   <li>{@code POST /api/model/roots}</li>
 *   <li>{@code PUT /api/model/{modelKey}/info}</li>
 *   <li>{@code DELETE /api/model/{modelKey}} (model scope)</li>
 * </ul>
 */
public interface ModelRootCommandPort {

    /**
     * root model 생성 명령입니다.
     *
     * @param modelName 생성할 모델 이름
     * @param commInterface 통신 인터페이스
     * @param maker 제조사
     * @param currentUser 현재 로그인 사용자 ID
     */
    record CreateRootModelCommand(
            String modelName,
            ProtocolType commInterface,
            String maker,
            String currentUser
    ) {
    }

    /**
     * model 정보 수정 명령입니다.
     *
     * <p>현재 정책상 maker만 수정 가능합니다.</p>
     *
     * @param modelKey 수정 대상 model_key
     * @param maker 변경할 제조사
     * @param currentUser 현재 로그인 사용자 ID
     */
    record UpdateRootModelInfoCommand(
            long modelKey,
            String maker,
            String currentUser
    ) {
    }

    /**
     * root model을 생성합니다.
     *
     * @param command 생성 명령
     * @return 생성된 최신 모델 버전 스냅샷
     */
    TcModel createRootModel(CreateRootModelCommand command);

    /**
     * root model의 공통 정보를 수정합니다.
     *
     * @param command 수정 명령
     * @return 수정 후 최신 모델 버전 스냅샷
     */
    TcModel updateRootModelInfo(UpdateRootModelInfoCommand command);

    /**
     * model_key 기준으로 모델을 삭제합니다.
     *
     * <p>root model이면 branch까지 cascade delete가 발생하고,
     * branch model이면 해당 branch만 삭제합니다.</p>
     *
     * @param modelKey 삭제 대상 model_key
     */
    void deleteModel(long modelKey);
}
