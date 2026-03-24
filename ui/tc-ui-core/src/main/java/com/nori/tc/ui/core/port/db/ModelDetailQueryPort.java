package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.domain.model.UiModelDcopItem;
import com.nori.tc.ui.domain.model.UiModelEventId;
import com.nori.tc.ui.domain.model.UiModelMdf;
import com.nori.tc.ui.domain.model.UiModelMesMessage;
import com.nori.tc.ui.domain.model.UiModelParam;
import com.nori.tc.ui.domain.model.UiModelReportId;
import com.nori.tc.ui.domain.model.UiModelSecsMessage;
import com.nori.tc.ui.domain.model.UiModelSocketMessage;
import com.nori.tc.ui.domain.model.UiModelVariableId;
import com.nori.tc.ui.domain.model.UiModelWorkflow;

import java.util.List;
import java.util.Optional;

/**
 * Model 상세 노드 조회 포트입니다.
 *
 * <p>Model 상세 화면의 좌측 노드 선택에 따라 필요한 테이블 데이터를 조회합니다.</p>
 * <ul>
 *   <li>Model Parameter: tc_model_param</li>
 *   <li>SECS Message: tc_model_secs_message</li>
 *   <li>Socket Message: tc_model_socket_message</li>
 *   <li>MES Message: tc_model_mes_message</li>
 *   <li>VariableIdes: tc_model_variableid</li>
 *   <li>ReportIdes: tc_model_reportid</li>
 *   <li>EventIdes: tc_model_eventid</li>
 *   <li>Workflow: tc_model_workflow</li>
 *   <li>MDF: tc_model_mdf</li>
 *   <li>Dcop Itemes: tc_model_dcop_item</li>
 * </ul>
 */
public interface ModelDetailQueryPort {

    /**
     * model_version_key 기준 Model Parameter 목록을 조회합니다.
     */
    List<UiModelParam> findParamsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 SECS Message 목록을 조회합니다.
     */
    List<UiModelSecsMessage> findSecsMessagesByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 Socket Message 목록을 조회합니다.
     */
    List<UiModelSocketMessage> findSocketMessagesByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 MES Message 목록을 조회합니다.
     */
    List<UiModelMesMessage> findMesMessagesByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 VariableIdes 목록을 조회합니다.
     */
    List<UiModelVariableId> findVariableIdsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 ReportIdes 목록을 조회합니다.
     */
    List<UiModelReportId> findReportIdsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 EventIdes 목록을 조회합니다.
     */
    List<UiModelEventId> findEventIdsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 Workflow 목록을 조회합니다.
     */
    List<UiModelWorkflow> findWorkflowsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 MDF를 조회합니다.
     */
    Optional<UiModelMdf> findMdfByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 Dcop Itemes 목록을 조회합니다.
     */
    List<UiModelDcopItem> findDcopItemsByModelVersionKey(long modelVersionKey);
}
