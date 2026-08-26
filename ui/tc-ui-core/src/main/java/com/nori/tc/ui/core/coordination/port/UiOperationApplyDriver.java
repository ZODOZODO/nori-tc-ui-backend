package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;

/**
 * {@code APPLYING} 단계의 명령 발행 전략입니다(§13.6).
 *
 * <p>기본 발행은 manifest 하나를 그대로 내보내는 {@link UiOperationCommandPublisher#publishApply}
 * 입니다. deploy 처럼 <b>순서가 있는</b> 적용은 그 순서를 지켜야 하므로 이 포트가 대신 발행합니다.</p>
 *
 * <p>구현은 <b>블로킹하면 안 됩니다</b>. executor lease TTL 은 기본 15s 인데 deploy step 하나는
 * 60s 까지 기다릴 수 있어, 응답을 붙잡고 기다리면 lease 가 만료되어 다른 Pod 가 같은 operation 을
 * 중복 전진시킵니다. 한 호출은 "지금까지의 응답을 확인하고, 보낼 것이 있으면 하나 보내고" 즉시
 * 반환해야 합니다. 남은 진행은 다음 reconcile 주기가 이어받습니다.</p>
 */
public interface UiOperationApplyDriver {

    /** 한 번의 발행 시도 결과입니다. */
    enum Progress {
        /** 이 driver 가 다룰 operation 이 아닙니다 — 호출 측이 기본 발행으로 되돌아갑니다. */
        NOT_MINE,
        /** 아직 응답을 기다리는 중입니다. 이번 패스에서 한 일이 없습니다. */
        WAITING,
        /** 이번 패스에서 명령을 발행했습니다. */
        ADVANCED,
        /** 발행할 것이 남지 않았습니다 — participant proof 수집 단계로 넘어가면 됩니다. */
        EXHAUSTED,
        /** 적용이 실패했습니다. 커밋 이후이므로 보상 경로로 넘겨야 합니다. */
        FAILED
    }

    /**
     * 한 패스의 결과입니다.
     *
     * @param progress 진행 상태
     * @param errorCode 실패 코드({@link Progress#FAILED} 일 때만)
     * @param errorMessage 실패 메시지({@link Progress#FAILED} 일 때만)
     */
    record Result(Progress progress, String errorCode, String errorMessage) {

        /**
         * 실패가 아닌 결과를 만듭니다.
         *
         * @param progress 진행 상태
         * @return 결과
         */
        public static Result of(final Progress progress) {
            return new Result(progress, null, null);
        }

        /**
         * 실패 결과를 만듭니다.
         *
         * @param errorCode 실패 코드
         * @param errorMessage 실패 메시지
         * @return 결과
         */
        public static Result failed(final String errorCode, final String errorMessage) {
            return new Result(Progress.FAILED, errorCode, errorMessage);
        }
    }

    /**
     * 이번 패스에서 발행 가능한 만큼만 진행합니다.
     *
     * @param operationId 대상 operation
     * @param manifest 원장에 고정된 manifest 뷰
     * @param desiredRevision 발급된 desired revision
     * @param manifestSha256 manifest digest
     * @param deadlineAt 관찰 deadline(ISO-8601)
     * @param nowEpochMs 현재 시각
     * @return 진행 결과
     */
    Result driveApply(
            String operationId,
            UiOperationManifestView manifest,
            long desiredRevision,
            String manifestSha256,
            String deadlineAt,
            long nowEpochMs);

    /**
     * 순서 있는 적용을 다루지 않는 구현을 반환합니다.
     *
     * <p>{@code null} 대신 이 값을 넘깁니다 — 호출 측이 null 검사를 흩뿌리지 않아도 되고, 발행 전략을
     * 빠뜨렸는지 아니면 일부러 두지 않았는지가 호출 지점에서 드러납니다.</p>
     *
     * @return 항상 {@link Progress#NOT_MINE} 을 반환하는 driver
     */
    static UiOperationApplyDriver none() {
        return (operationId, manifest, desiredRevision, manifestSha256, deadlineAt, nowEpochMs) ->
                Result.of(Progress.NOT_MINE);
    }
}
