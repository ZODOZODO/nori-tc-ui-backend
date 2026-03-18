package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelEventId;
import com.nori.tc.db.domain.model.TcModelMdf;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.db.domain.model.TcModelReportId;
import com.nori.tc.db.domain.model.TcModelMesMessage;
import com.nori.tc.db.domain.model.TcModelSecsMessage;
import com.nori.tc.db.domain.model.TcModelSocketMessage;
import com.nori.tc.db.domain.model.TcModelVariableId;
import com.nori.tc.db.domain.model.TcModelWorkflow;

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
    List<TcModelParam> findParamsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 SECS Message 목록을 조회합니다.
     */
    List<TcModelSecsMessage> findSecsMessagesByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 Socket Message 목록을 조회합니다.
     */
    List<TcModelSocketMessage> findSocketMessagesByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 MES Message 목록을 조회합니다.
     */
    List<TcModelMesMessage> findMesMessagesByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 VariableIdes 목록을 조회합니다.
     */
    List<TcModelVariableId> findVariableIdsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 ReportIdes 목록을 조회합니다.
     */
    List<TcModelReportId> findReportIdsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 EventIdes 목록을 조회합니다.
     */
    List<TcModelEventId> findEventIdsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 Workflow 목록을 조회합니다.
     */
    List<TcModelWorkflow> findWorkflowsByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 MDF를 조회합니다.
     */
    Optional<TcModelMdf> findMdfByModelVersionKey(long modelVersionKey);

    /**
     * model_version_key 기준 Dcop Itemes 목록을 조회합니다.
     */
    List<TcModelDcopItem> findDcopItemsByModelVersionKey(long modelVersionKey);
}
