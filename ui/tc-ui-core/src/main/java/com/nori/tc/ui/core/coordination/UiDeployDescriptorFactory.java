package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiRuntimeDescriptor;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployDesiredState;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;

import java.util.Objects;

/**
 * deploy target 의 plan draft 를 candidate descriptor 로 옮깁니다(§13.6).
 *
 * <p>{@link UiEqpDescriptorFactory} 와 같은 계약을 따릅니다 — {@code null} 은 "이번 revision 이 그
 * 측면을 바꾸지 않음" 입니다. planner 가 목표 상태를 비워 둔 필드는 그대로 비워서 넘깁니다. 채우면
 * 바뀌지 않은 값까지 소비자가 재적재합니다.</p>
 *
 * <p>{@code businessJarSha256} 은 싣지 않습니다. Business reconciler 는 descriptor 에 digest 가
 * 있으면 그 operation 이 modelVersionKey 공유 상태를 바꾼다고 보고 영향 집합 폐쇄를 요구하는데
 * (§15.2), deploy 의 폐쇄는 접수 단계에서 {@link UiResourceKeyPlanner} 가 이미 산출합니다. 여기서
 * digest 까지 실으면 같은 요구를 두 곳에서 하게 되고, planner 가 폐쇄하지 않는 이벤트에서는 그 하나만
 * 잠근 채 폐쇄를 요구받아 prepare 에서 거부됩니다.</p>
 */
public final class UiDeployDescriptorFactory {

    /**
     * deploy target 자신의 목표 descriptor 를 만듭니다.
     *
     * @param planDraft target 의 plan 초안
     * @return candidate descriptor
     */
    public UiRuntimeDescriptor forDeployTarget(final DeployTargetPlanDraft planDraft) {
        Objects.requireNonNull(planDraft, "planDraft is null");
        final DeployCurrentState current = planDraft.currentState();
        final DeployDesiredState desired = planDraft.desiredState();

        return new UiRuntimeDescriptor(
                planDraft.eqpId(),
                false,
                current == null ? null : current.eqpKey(),
                desired.modelVersionKey(),
                desired.routePartition(),
                desired.businessJarRef(),
                null,
                null,
                null,
                null,
                pluginRuntimeAffected(current, desired),
                null,
                null);
    }

    /**
     * deploy target 의 변경 전 descriptor 를 만듭니다(§13.7 expected old / §13.8 보상 원본).
     *
     * @param planDraft target 의 plan 초안
     * @return 변경 전 descriptor(현재 상태를 모르면 null)
     */
    public UiRuntimeDescriptor forDeployTargetPrevious(final DeployTargetPlanDraft planDraft) {
        Objects.requireNonNull(planDraft, "planDraft is null");
        final DeployCurrentState current = planDraft.currentState();
        if (current == null) {
            /*
             * 되돌릴 근거가 없으면 previousDescriptor 를 만들지 않는다. 보상 발급기는 전 리소스를
             * 덮지 못하면 수동 복구로 남긴다(§13.8) — 절반만 되돌리는 것보다 낫다.
             */
            return null;
        }
        return new UiRuntimeDescriptor(
                planDraft.eqpId(),
                false,
                current.eqpKey(),
                current.modelVersionKey(),
                current.routePartition(),
                current.businessJarRef(),
                null,
                null,
                null,
                null,
                true,
                null,
                null);
    }

    /**
     * 폐쇄로만 끌려온 설비의 descriptor 를 만듭니다(§7.3).
     *
     * <p>이 설비들은 배포 대상이 아니라 같은 {@code modelVersionKey} 를 공유한다는 이유로 잠깁니다.
     * 그러므로 model 공유 필드만 목표 값으로 맞추고 그 설비 고유의 Gateway 필드는 건드리지 않습니다.</p>
     *
     * @param eqpId 폐쇄로 끌려온 설비
     * @param modelVersionKey 그룹의 목표 modelVersionKey
     * @param businessJarKey 그룹의 목표 Business JAR key
     * @return candidate descriptor
     */
    public UiRuntimeDescriptor forClosureOnly(
            final String eqpId,
            final Long modelVersionKey,
            final Long businessJarKey
    ) {
        Objects.requireNonNull(eqpId, "eqpId is null");
        return new UiRuntimeDescriptor(
                eqpId,
                false,
                null,
                modelVersionKey,
                null,
                businessJarKey,
                null,
                null,
                null,
                null,
                true,
                null,
                null);
    }

    /**
     * plugin runtime 재적재가 필요한 변경인지 판정합니다.
     *
     * <p>모르면 {@code true} 입니다 — 재적재를 건너뛰어 옛 JAR 로 처리가 계속되는 쪽이 더 위험합니다.</p>
     */
    private static boolean pluginRuntimeAffected(
            final DeployCurrentState current,
            final DeployDesiredState desired
    ) {
        if (current == null) {
            return true;
        }
        if (desired.modelVersionKey() != null && desired.modelVersionKey() != current.modelVersionKey()) {
            return true;
        }
        return desired.businessJarRef() != null
                && !desired.businessJarRef().equals(current.businessJarRef());
    }
}
