package com.nori.tc.ui.adapter.db.mapper;

import com.nori.tc.ui.domain.auth.UiUserStatus;
import com.nori.tc.ui.domain.common.eqp.UiCarrierState;
import com.nori.tc.ui.domain.common.eqp.UiCarrierType;
import com.nori.tc.ui.domain.common.eqp.UiControlState;
import com.nori.tc.ui.domain.common.eqp.UiEqpState;
import com.nori.tc.ui.domain.common.eqp.UiLogLevel;
import com.nori.tc.ui.domain.common.eqp.UiPortType;
import com.nori.tc.ui.domain.common.model.UiDcopCollectionRule;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.common.model.UiVariableIdType;
import com.nori.tc.ui.domain.common.user.UiPermissionMatchType;
import com.nori.tc.ui.domain.common.user.UiPermissionResourceType;

/**
 * DB enum과 UI 내부 enum 간 변환을 담당하는 ACL 매퍼입니다.
 */
public final class UiDomainEnumMapper {

    private UiDomainEnumMapper() {
    }

    public static UiProtocolType toUiProtocolType(final com.nori.tc.db.domain.common.model.ProtocolType source) {
        return source == null ? null : UiProtocolType.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.model.ProtocolType toDbProtocolType(final UiProtocolType source) {
        return source == null ? null : com.nori.tc.db.domain.common.model.ProtocolType.valueOf(source.name());
    }

    public static UiModelStatus toUiModelStatus(final com.nori.tc.db.domain.common.model.ModelStatus source) {
        return source == null ? null : UiModelStatus.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.model.ModelStatus toDbModelStatus(final UiModelStatus source) {
        return source == null ? null : com.nori.tc.db.domain.common.model.ModelStatus.valueOf(source.name());
    }

    public static UiVariableIdType toUiVariableIdType(final com.nori.tc.db.domain.common.model.VariableIdType source) {
        return source == null ? null : UiVariableIdType.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.model.VariableIdType toDbVariableIdType(final UiVariableIdType source) {
        return source == null ? null : com.nori.tc.db.domain.common.model.VariableIdType.valueOf(source.name());
    }

    public static UiDcopCollectionRule toUiDcopCollectionRule(final com.nori.tc.db.domain.common.model.DcopCollectionRule source) {
        return source == null ? null : UiDcopCollectionRule.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.model.DcopCollectionRule toDbDcopCollectionRule(
            final UiDcopCollectionRule source
    ) {
        return source == null ? null : com.nori.tc.db.domain.common.model.DcopCollectionRule.valueOf(source.name());
    }

    public static UiLogLevel toUiLogLevel(final com.nori.tc.db.domain.common.eqp.LogLevel source) {
        return source == null ? null : UiLogLevel.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.eqp.LogLevel toDbLogLevel(final UiLogLevel source) {
        return source == null ? null : com.nori.tc.db.domain.common.eqp.LogLevel.valueOf(source.name());
    }

    public static UiControlState toUiControlState(final com.nori.tc.db.domain.common.eqp.ControlState source) {
        return source == null ? null : UiControlState.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.eqp.ControlState toDbControlState(final UiControlState source) {
        return source == null ? null : com.nori.tc.db.domain.common.eqp.ControlState.valueOf(source.name());
    }

    public static UiEqpState toUiEqpState(final com.nori.tc.db.domain.common.eqp.EqpState source) {
        return source == null ? null : UiEqpState.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.eqp.EqpState toDbEqpState(final UiEqpState source) {
        return source == null ? null : com.nori.tc.db.domain.common.eqp.EqpState.valueOf(source.name());
    }

    public static UiPortType toUiPortType(final com.nori.tc.db.domain.common.eqp.PortType source) {
        return source == null ? null : UiPortType.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.eqp.PortType toDbPortType(final UiPortType source) {
        return source == null ? null : com.nori.tc.db.domain.common.eqp.PortType.valueOf(source.name());
    }

    public static UiCarrierType toUiCarrierType(final com.nori.tc.db.domain.common.eqp.CarrierType source) {
        return source == null ? null : UiCarrierType.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.eqp.CarrierType toDbCarrierType(final UiCarrierType source) {
        return source == null ? null : com.nori.tc.db.domain.common.eqp.CarrierType.valueOf(source.name());
    }

    public static UiCarrierState toUiCarrierState(final com.nori.tc.db.domain.common.eqp.CarrierState source) {
        return source == null ? null : UiCarrierState.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.eqp.CarrierState toDbCarrierState(final UiCarrierState source) {
        return source == null ? null : com.nori.tc.db.domain.common.eqp.CarrierState.valueOf(source.name());
    }

    public static UiPermissionResourceType toUiPermissionResourceType(
            final com.nori.tc.db.domain.common.user.TcUiPermissionResourceType source
    ) {
        return source == null ? null : UiPermissionResourceType.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.user.TcUiPermissionResourceType toDbPermissionResourceType(
            final UiPermissionResourceType source
    ) {
        return source == null ? null : com.nori.tc.db.domain.common.user.TcUiPermissionResourceType.valueOf(source.name());
    }

    public static UiPermissionMatchType toUiPermissionMatchType(
            final com.nori.tc.db.domain.common.user.TcUiPermissionMatchType source
    ) {
        return source == null ? null : UiPermissionMatchType.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.user.TcUiPermissionMatchType toDbPermissionMatchType(
            final UiPermissionMatchType source
    ) {
        return source == null ? null : com.nori.tc.db.domain.common.user.TcUiPermissionMatchType.valueOf(source.name());
    }

    public static UiUserStatus toUiUserStatus(final com.nori.tc.db.domain.common.user.UserStatus source) {
        return source == null ? null : UiUserStatus.valueOf(source.name());
    }

    public static com.nori.tc.db.domain.common.user.UserStatus toDbUserStatus(final UiUserStatus source) {
        return source == null ? null : com.nori.tc.db.domain.common.user.UserStatus.valueOf(source.name());
    }
}
