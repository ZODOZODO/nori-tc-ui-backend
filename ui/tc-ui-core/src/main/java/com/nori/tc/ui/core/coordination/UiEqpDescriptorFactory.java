package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiRuntimeDescriptor;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 설비 mutation 의 candidate descriptor 를 만듭니다(§11.1 Business + §12.3 Gateway).
 *
 * <p>필드를 <b>비우는 것도 계약</b>입니다. {@code null} 은 "이번 revision 이 그 측면을 바꾸지 않음",
 * 빈 컬렉션은 "빈 값으로 교체" 를 뜻하므로, 바뀌지 않은 값을 굳이 채우면 소비자가 불필요한 재적재를
 * 수행하고, 바뀐 값을 비우면 Gateway overlay 가 조용히 DB baseline 으로 떨어집니다.</p>
 */
public final class UiEqpDescriptorFactory {

    /**
     * 대상 설비의 descriptor 를 만듭니다.
     *
     * @param eqpId 대상 설비
     * @param before 변경 전 스냅샷(신규 생성이면 null)
     * @param modelVersionKey 목표 modelVersionKey
     * @param routePartition 목표 route partition(미변경이면 null)
     * @param businessJarKey 목표 Business JAR key(미변경이면 null)
     * @param businessJarFileName 목표 Business JAR 파일명(미변경이면 null)
     * @param eqpParams 목표 설비 파라미터 snapshot(미변경이면 null)
     * @param gatewayDesiredState Gateway 연결 의도(미변경이면 null)
     * @param plcEnabledPollGroups 활성 폴 그룹(미변경이면 null, 빈 목록이면 전부 해제)
     * @return candidate descriptor
     */
    public UiRuntimeDescriptor forTarget(
            final String eqpId,
            final EqpManagementSnapshot before,
            final Long modelVersionKey,
            final Integer routePartition,
            final Long businessJarKey,
            final String businessJarFileName,
            final Map<String, String> eqpParams,
            final String gatewayDesiredState,
            final List<String> plcEnabledPollGroups
    ) {
        Objects.requireNonNull(eqpId, "eqpId is null");
        final UiEqp existing = before == null ? null : before.eqp();
        final boolean pluginAffected = pluginRuntimeAffected(existing, modelVersionKey, businessJarKey);
        return new UiRuntimeDescriptor(
                eqpId,
                false,
                existing == null ? null : existing.eqpKey(),
                modelVersionKey,
                routePartition,
                businessJarKey,
                businessJarFileName,
                null,
                null,
                eqpParams,
                pluginAffected,
                gatewayDesiredState,
                plcEnabledPollGroups);
    }

    /**
     * 삭제 tombstone descriptor 를 만듭니다.
     *
     * <p>tombstone 을 revisioned descriptor 로 남겨야 재기동한 Pod 가 DB 의 옛 row 를 되살리지 못합니다
     * (§11.2). Gateway 에는 연결 의도를 {@code ENDED} 로 알려 채널을 열지 않게 합니다.</p>
     *
     * @param eqpId 대상 설비
     * @param before 변경 전 스냅샷
     * @return tombstone descriptor
     */
    public UiRuntimeDescriptor forDelete(final String eqpId, final EqpManagementSnapshot before) {
        Objects.requireNonNull(eqpId, "eqpId is null");
        final UiEqp existing = before == null ? null : before.eqp();
        return new UiRuntimeDescriptor(
                eqpId,
                true,
                existing == null ? null : existing.eqpKey(),
                existing == null ? null : existing.modelVersionKey(),
                existing == null ? null : existing.routePartition(),
                null,
                null,
                null,
                null,
                null,
                true,
                UiRuntimeDescriptor.GATEWAY_STATE_ENDED,
                List.of());
    }

    /**
     * 같은 modelVersionKey 를 공유하는 <b>다른</b> 설비의 descriptor 를 만듭니다(§7.3 영향 집합).
     *
     * <p>이 설비들은 model 공유 상태만 바뀝니다 — Gateway 필드를 채우면 바꾸지 않아도 될 연결 의도를
     * 건드리게 되므로 전부 비웁니다.</p>
     *
     * @param eqpId 영향 설비
     * @param snapshot 그 설비의 현재 스냅샷(없으면 null)
     * @param modelVersionKey 목표 modelVersionKey
     * @param businessJarKey 목표 Business JAR key
     * @param businessJarFileName 목표 Business JAR 파일명
     * @return candidate descriptor
     */
    public UiRuntimeDescriptor forSharedModel(
            final String eqpId,
            final EqpManagementSnapshot snapshot,
            final Long modelVersionKey,
            final Long businessJarKey,
            final String businessJarFileName
    ) {
        Objects.requireNonNull(eqpId, "eqpId is null");
        final UiEqp existing = snapshot == null ? null : snapshot.eqp();
        return new UiRuntimeDescriptor(
                eqpId,
                false,
                existing == null ? null : existing.eqpKey(),
                modelVersionKey,
                null,
                businessJarKey,
                businessJarFileName,
                null,
                null,
                null,
                true,
                null,
                null);
    }

    /**
     * 설비 파라미터 목록을 descriptor snapshot 형태로 정규화합니다.
     *
     * @param params 설비 파라미터
     * @return 이름 → 값 map(입력이 null 이면 null)
     */
    public Map<String, String> toParamSnapshot(final List<UiEqpParam> params) {
        if (params == null) {
            return null;
        }
        final Map<String, String> snapshot = new LinkedHashMap<>();
        for (final UiEqpParam param : params) {
            snapshot.put(param.paramName(), param.paramValue());
        }
        return snapshot;
    }

    /**
     * plugin runtime 재적재가 필요한 변경인지 판정합니다.
     *
     * <p>모르면 {@code true} 입니다 — 재적재를 건너뛰어 옛 JAR 로 처리가 계속되는 쪽이 더 위험합니다.</p>
     */
    private static boolean pluginRuntimeAffected(
            final UiEqp existing,
            final Long modelVersionKey,
            final Long businessJarKey
    ) {
        if (existing == null) {
            return true;
        }
        if (modelVersionKey != null && modelVersionKey != existing.modelVersionKey()) {
            return true;
        }
        return businessJarKey != null && !businessJarKey.equals(existing.businessJarKey());
    }
}
