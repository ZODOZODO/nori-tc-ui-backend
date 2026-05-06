package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.commlog.CommLogEntry;
import com.nori.tc.ui.core.port.redis.CommLogQueryPort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 설비별 통신 로그를 조회하는 서비스입니다.
 */
@Service
public class CommLogQueryService {

    private final CommLogQueryPort commLogQueryPort;

    public CommLogQueryService(final CommLogQueryPort commLogQueryPort) {
        this.commLogQueryPort = Objects.requireNonNull(commLogQueryPort, "commLogQueryPort is null");
    }

    /**
     * 설비의 최근 1시간 설비 통신 로그를 반환합니다.
     *
     * @param eqpId 설비 식별자
     * @return 시간순 정렬된 설비 통신 로그
     */
    public List<CommLogEntry> getEqpLogs(final String eqpId) {
        return commLogQueryPort.findEqpLogs(eqpId);
    }

    /**
     * 설비의 최근 1시간 MES 통신 로그를 반환합니다.
     *
     * @param eqpId 설비 식별자
     * @return 시간순 정렬된 MES 통신 로그
     */
    public List<CommLogEntry> getMesLogs(final String eqpId) {
        return commLogQueryPort.findMesLogs(eqpId);
    }

    /**
     * 설비의 설비 통신 로그와 MES 통신 로그를 합쳐 시간순으로 반환합니다.
     *
     * @param eqpId 설비 식별자
     * @return 시간순 정렬된 전체 통신 로그
     */
    public List<CommLogEntry> getAllLogs(final String eqpId) {
        final List<CommLogEntry> combined = new ArrayList<>();
        combined.addAll(commLogQueryPort.findEqpLogs(eqpId));
        combined.addAll(commLogQueryPort.findMesLogs(eqpId));
        combined.sort(Comparator.comparingLong(CommLogEntry::timestampMs));
        return combined;
    }
}
