package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.CoordinationKeys;
import com.nori.tc.common.coordination.store.CoordinationOutcome;
import com.nori.tc.common.coordination.store.ResourceBarrierRedisStore;
import com.nori.tc.common.coordination.types.ResourceHead;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UiResourceHeadPort} 의 Redis 구현입니다(§7.3/§18.1).
 */
public final class RedisUiResourceHeadAdapter implements UiResourceHeadPort {

    private final ResourceBarrierRedisStore store;

    /**
     * 어댑터를 만듭니다.
     *
     * @param store 공통 리소스 장벽 store
     */
    public RedisUiResourceHeadAdapter(final ResourceBarrierRedisStore store) {
        this.store = Objects.requireNonNull(store, "store is null");
    }

    @Override
    public IssueResult issueCandidate(
            final String operationId,
            final List<Candidate> candidates,
            final long nowEpochMs
    ) {
        final List<ResourceBarrierRedisStore.ResourceCandidate> mapped = candidates.stream()
                .map(candidate -> new ResourceBarrierRedisStore.ResourceCandidate(
                        candidate.resourceType(),
                        candidate.resourceId(),
                        candidate.expectedBaseRevision(),
                        candidate.descriptorJson()))
                .toList();
        final CoordinationOutcome outcome = store.issueCandidate(operationId, mapped, nowEpochMs);
        return switch (outcome.code()) {
            case "OK" -> new IssueResult(IssueCode.OK, parseLong(outcome.value(0)), null);
            case "ALREADY_ISSUED" -> new IssueResult(IssueCode.ALREADY_ISSUED, parseLong(outcome.value(0)), null);
            case "RESOURCE_BUSY" -> new IssueResult(IssueCode.RESOURCE_BUSY, null, emptyToNull(outcome.value(0)));
            case "RESOURCE_BLOCKED" -> new IssueResult(IssueCode.RESOURCE_BLOCKED, null, emptyToNull(outcome.value(0)));
            default -> new IssueResult(IssueCode.REVISION_CONFLICT, parseLong(outcome.value(0)), null);
        };
    }

    @Override
    public UiResourceHeadView readHead(final String resourceType, final String resourceId) {
        final ResourceHead head = store.readHead(resourceType, resourceId);
        return new UiResourceHeadView(
                head.resourceKey(),
                head.committedRevision(),
                head.openRevision(),
                head.blockedByOperationId(),
                head.activeOperationId());
    }

    @Override
    public Optional<String> readDescriptor(
            final String resourceType,
            final String resourceId,
            final long revision
    ) {
        return store.readDescriptor(resourceType, resourceId, revision);
    }

    @Override
    public String headKeyOf(final String resourceKey) {
        final int separator = Objects.requireNonNull(resourceKey, "resourceKey is null").indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("malformed resourceKey: " + resourceKey);
        }
        return CoordinationKeys.resourceHeadKey(
                resourceKey.substring(0, separator), resourceKey.substring(separator + 1));
    }

    private static Long parseLong(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String emptyToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
