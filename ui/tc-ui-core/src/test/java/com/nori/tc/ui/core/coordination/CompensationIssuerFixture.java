package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.port.UiCoordinationDigestPort;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 보상 발급기(§13.8)를 <b>실물로</b> 조립하는 테스트 fixture 입니다.
 *
 * <p>보상을 다루지 않는 테스트에도 실물을 씁니다 — 아무 것도 하지 않는 대역을 끼우면
 * {@code REPAIR_REQUIRED} 경로가 보상 발급을 시도조차 하지 않는 회귀를 테스트가 통과시킵니다.
 * 실물은 주어진 원장에 manifest 가 없으면 발급을 포기하므로 그 자체가 안전합니다.</p>
 */
final class CompensationIssuerFixture {

    private CompensationIssuerFixture() {
    }

    static UiCompensationIssuer issuer(
            final UiOperationLedgerPort ledgerPort,
            final UiResourceHeadPort resourceHeadPort
    ) {
        final ObjectMapper objectMapper = new ObjectMapper();
        final UiCoordinationDigestPort digestPort = new UiCoordinationDigestPort() {
            @Override
            public String canonicalize(final String json) {
                return json;
            }

            @Override
            public String sha256Hex(final String canonicalJson) {
                try {
                    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
        return new UiCompensationIssuer(
                ledgerPort,
                resourceHeadPort,
                new UiOperationManifestFactory(
                        objectMapper, new UiRuntimeDescriptorCodec(objectMapper), digestPort),
                new UiOperationManifestReader(objectMapper));
    }
}
