package com.nori.tc.ui.core.coordination.port;

/**
 * 조정 원장의 atomic script 실행 가능 여부를 확인하는 포트입니다(§13.11).
 *
 * <p>Redis {@code PING} 만으로는 부족합니다 — 연결은 살아 있는데 script 실행이나 쓰기가 실패하는
 * 상태에서 Ready 로 두면 mutation 을 받아 들이고 그 자리에서 실패합니다.</p>
 */
public interface UiCoordinationProbePort {

    /**
     * atomic script 의 읽기/쓰기를 실제로 수행해 봅니다.
     *
     * @return 성공하면 true
     */
    boolean verifyAtomicScriptReadWrite();

    /**
     * 기동 시 확인한 cluster epoch 이 아직 그대로인지 확인합니다.
     *
     * @return 같으면 true
     */
    boolean clusterEpochUnchanged();
}
