package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.port.UiCoordinationDigestPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;

/**
 * operation 의 불변 manifest 와 digest 를 만듭니다(§8.4/§13.1).
 *
 * <p>manifest 는 이 operation 의 "무엇을 하려는가" 를 통째로 담아 digest 한 문서입니다. 같은
 * idempotency key 로 다른 manifest 가 오면 digest 가 달라져 409 로 거부됩니다 — 그러려면 결과를
 * 바꿀 수 있는 값이 전부 들어 있어야 합니다.</p>
 *
 * <p>비밀값(token/credential)은 담지 않습니다(§8.4/§21).</p>
 */
public final class UiOperationManifestFactory {

    /** manifest schema version 입니다. */
    public static final int SCHEMA_VERSION = 2;

    private final ObjectMapper objectMapper;
    private final UiRuntimeDescriptorCodec descriptorCodec;
    private final UiCoordinationDigestPort digestPort;

    /**
     * 팩토리를 만듭니다.
     *
     * @param objectMapper JSON 직렬화기
     * @param descriptorCodec descriptor 직렬화기
     * @param digestPort canonical digest 포트
     */
    public UiOperationManifestFactory(
            final ObjectMapper objectMapper,
            final UiRuntimeDescriptorCodec descriptorCodec,
            final UiCoordinationDigestPort digestPort
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.descriptorCodec = Objects.requireNonNull(descriptorCodec, "descriptorCodec is null");
        this.digestPort = Objects.requireNonNull(digestPort, "digestPort is null");
    }

    /**
     * canonical manifest 와 그 digest 입니다.
     *
     * @param canonicalJson canonical manifest JSON
     * @param sha256 manifest digest
     */
    public record Manifest(String canonicalJson, String sha256) {
    }

    /**
     * 요청으로부터 canonical manifest 를 만듭니다.
     *
     * @param request mutation 요청
     * @return canonical manifest 와 digest
     */
    public Manifest build(final UiOperationRequest request) {
        Objects.requireNonNull(request, "request is null");

        final ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("eventType", request.eventType());
        root.put("eqpId", request.eqpId());
        if (request.actor() != null && !request.actor().isBlank()) {
            root.put("actor", request.actor());
        }
        if (request.executionId() != null && !request.executionId().isBlank()) {
            root.put("executionId", request.executionId());
        }
        if (request.catalogRevision() != null) {
            root.put("catalogRevision", request.catalogRevision());
        }
        if (request.catalogDigest() != null && !request.catalogDigest().isBlank()) {
            root.put("catalogDigest", request.catalogDigest());
        }
        root.put("requiresGatewayParticipant", request.requiresGatewayParticipant());
        if (request.interfaceType() != null && !request.interfaceType().isBlank()) {
            root.put("interfaceType", request.interfaceType());
        }

        final ArrayNode resourceKeys = root.putArray("resourceKeys");
        request.resourceKeys().forEach(resourceKeys::add);

        final ObjectNode descriptors = root.putObject("descriptors");
        final ObjectNode baseRevisions = root.putObject("expectedBaseRevisions");
        final ObjectNode previousDescriptors = root.putObject("previousDescriptors");
        for (final String resourceKey : request.resourceKeys()) {
            descriptors.set(resourceKey,
                    objectMapper.readTree(descriptorCodec.write(request.descriptors().get(resourceKey))));
            baseRevisions.put(resourceKey, request.expectedBaseRevisions().get(resourceKey));
            if (request.previousDescriptors().containsKey(resourceKey)) {
                previousDescriptors.set(resourceKey,
                        objectMapper.readTree(descriptorCodec.write(request.previousDescriptors().get(resourceKey))));
            }
        }

        if (request.dbCommandKind() != null && !request.dbCommandKind().isBlank()) {
            root.put("dbCommandKind", request.dbCommandKind());
        }
        if (request.dbCommandJson() != null && !request.dbCommandJson().isBlank()) {
            root.set("dbCommand", objectMapper.readTree(request.dbCommandJson()));
        }

        final String canonical = digestPort.canonicalize(objectMapper.writeValueAsString(root));
        return new Manifest(canonical, digestPort.sha256Hex(canonical));
    }

    /**
     * 실패한 operation 의 manifest 로부터 보상 manifest 를 만듭니다(§13.8).
     *
     * <p>목표와 이전 상태를 <b>맞바꾼</b> 문서입니다 — 되돌릴 목표는 원 operation 의 이전 상태이고,
     * 지금 런타임에 올라가 있는 것은 원 operation 의 목표 상태이므로 §13.7 의 expected old 판정도
     * 그대로 성립합니다.</p>
     *
     * <p>DB 명령은 원칙적으로 싣지 않습니다. 보상 대상이 되는 실패는 DB 확정 <b>이전</b> 단계에서만
     * 발생하므로 되돌릴 DB 변경이 없고, 역명령을 지어내면 확인되지 않은 상태를 덮어쓰게 됩니다.
     * 예외는 <b>목표가 tombstone 인 보상</b> 하나입니다 — 설비 생성은 §16.1 에 따라 PREPARE 전에 임시
     * 행을 만들므로 그 행을 지우는 {@code DELETE} 를 실어야 "임시 DB 상태 제거" 가 증명됩니다.</p>
     *
     * @param originalManifestJson 실패한 operation 의 canonical manifest
     * @param compensatesRevision 되돌릴 대상 revision
     * @param expectedBaseRevisions resourceKey 별 현재 committed revision
     * @return 보상 manifest 와 digest
     * @throws IllegalStateException 이전 상태 descriptor 가 영향 리소스 전부를 덮지 않을 때
     */
    public Manifest buildCompensation(
            final String originalManifestJson,
            final long compensatesRevision,
            final Map<String, Long> expectedBaseRevisions
    ) {
        final ObjectNode root = (ObjectNode) objectMapper.readTree(
                Objects.requireNonNull(originalManifestJson, "originalManifestJson is null"));
        final ObjectNode forward = (ObjectNode) root.get("descriptors");
        final ObjectNode previous = (ObjectNode) root.get("previousDescriptors");

        final ObjectNode baseRevisions = objectMapper.createObjectNode();
        for (final Map.Entry<String, Long> entry : expectedBaseRevisions.entrySet()) {
            if (previous == null || !previous.has(entry.getKey())) {
                throw new IllegalStateException(
                        "이전 상태 descriptor 가 없어 보상 manifest 를 만들 수 없습니다. resourceKey=" + entry.getKey());
            }
            baseRevisions.put(entry.getKey(), entry.getValue());
        }

        root.set("descriptors", previous);
        root.set("previousDescriptors", forward == null ? objectMapper.createObjectNode() : forward);
        root.set("expectedBaseRevisions", baseRevisions);
        root.put("compensatesRevision", compensatesRevision);
        root.remove("dbCommandKind");
        root.remove("dbCommand");
        if (revertsToTombstone(previous, root)) {
            /*
             * §16.1 — 설비 생성의 보상은 되돌릴 DB 변경이 있다. 생성 operation 은 PREPARE 전에 임시
             * 행을 만들므로(§16.1 2번째 규칙) 보상이 그 행을 지워야 "임시 DB 상태가 제거되었음" 이
             * 성립한다. 목표가 tombstone 인 보상에만 붙이므로 다른 종류의 역명령을 지어내지 않는다.
             */
            root.put("dbCommandKind", UiOperationRequest.DB_COMMAND_DELETE);
            root.set("dbCommand", objectMapper.createObjectNode());
        }

        final String canonical = digestPort.canonicalize(objectMapper.writeValueAsString(root));
        return new Manifest(canonical, digestPort.sha256Hex(canonical));
    }

    /**
     * 보상 목표가 대상 설비의 tombstone 인지 판정합니다.
     *
     * <p>대상 설비({@code eqpId})의 descriptor 만 봅니다 — 영향 집합에 끌려온 공유 설비의 descriptor 는
     * 삭제 대상이 아닙니다.</p>
     */
    private static boolean revertsToTombstone(final ObjectNode compensationTargets, final ObjectNode root) {
        if (compensationTargets == null) {
            return false;
        }
        final String eqpId = root.path("eqpId").asString(null);
        if (eqpId == null) {
            return false;
        }
        final JsonNode descriptor = compensationTargets.get("equipment:" + eqpId);
        return descriptor != null && descriptor.path("deleted").asBoolean(false);
    }

    /**
     * 한 리소스의 candidate descriptor JSON 을 반환합니다.
     *
     * <p>manifest 에 실린 것과 <b>같은 직렬화</b>여야 합니다 — 다르면 participant 가 검증한 descriptor 와
     * digest 로 고정된 manifest 가 어긋납니다.</p>
     *
     * @param request mutation 요청
     * @param resourceKey 대상 resourceKey
     * @return descriptor JSON
     */
    public String descriptorJson(final UiOperationRequest request, final String resourceKey) {
        return descriptorCodec.write(request.descriptors().get(resourceKey));
    }

    /**
     * idempotency key 로부터 결정적 operationId 를 만듭니다.
     *
     * <p>같은 key 재요청이 같은 operation 을 가리켜야 §13.2 의 "같은 operationId 와 현재/최종 상태를
     * 반환한다" 가 성립합니다. 무작위 id 를 쓰면 재요청마다 새 operation 이 생겨 idempotency 가
     * 깨집니다.</p>
     *
     * @param idempotencyKey 요청 idempotency key
     * @return operation 식별자
     */
    public String operationIdOf(final String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is null");
        return "op-" + digestPort.sha256Hex(idempotencyKey).substring(0, 32);
    }
}
