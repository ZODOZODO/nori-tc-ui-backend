package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.domain.model.UiModelPlcGroup;
import com.nori.tc.ui.domain.model.UiModelPlcRegister;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code tc_model_plc_*} 조회 결과({@link UiModelPlcGroup}/{@link UiModelPlcRegister})를 게이트웨이 운반용
 * {@link UiEquipmentProfileSnapshot.PlcGroupSnapshot} 디코드 스펙으로 조립하는 순수 변환기입니다.
 *
 * <p>레지스터는 평탄 목록이므로 {@code groupMessageName} 으로 소속 그룹에 재결합한다. deploy 발행 경로(forward)와
 * 보상/pre-state 스냅샷 경로가 동일 조립을 공유하므로 한 곳에 모은다(상태 없는 static).</p>
 */
final class PlcGroupSnapshotAssembler {

    private PlcGroupSnapshotAssembler() {
    }

    static List<UiEquipmentProfileSnapshot.PlcGroupSnapshot> assemble(
            final List<UiModelPlcGroup> groups,
            final List<UiModelPlcRegister> registers
    ) {
        final Map<String, List<UiEquipmentProfileSnapshot.PlcRegisterSnapshot>> registersByGroup = new LinkedHashMap<>();
        for (final UiModelPlcRegister register : registers) {
            registersByGroup
                    .computeIfAbsent(register.groupMessageName(), key -> new java.util.ArrayList<>())
                    .add(toRegisterSnapshot(register));
        }

        return groups.stream()
                .map(group -> new UiEquipmentProfileSnapshot.PlcGroupSnapshot(
                        group.messageName(),
                        group.area(),
                        group.startAddress(),
                        group.readCount(),
                        group.addressQualifier(),
                        group.pollPeriodMs(),
                        group.description(),
                        registersByGroup.getOrDefault(group.messageName(), List.of())
                ))
                .toList();
    }

    private static UiEquipmentProfileSnapshot.PlcRegisterSnapshot toRegisterSnapshot(final UiModelPlcRegister register) {
        return new UiEquipmentProfileSnapshot.PlcRegisterSnapshot(
                register.wordOffset(),
                register.variableName(),
                register.dataType(),
                register.length(),
                register.bitIndex(),
                register.byteIndex(),
                register.byteOrder(),
                register.wordOrder(),
                register.scale(),
                register.bcdSigned(),
                register.charEncoding(),
                register.description()
        );
    }
}
