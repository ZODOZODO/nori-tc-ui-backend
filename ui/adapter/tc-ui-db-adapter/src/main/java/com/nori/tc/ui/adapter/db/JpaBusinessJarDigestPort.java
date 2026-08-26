package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.jar.store.TcJarBusinessStore;
import com.nori.tc.ui.core.port.db.BusinessJarDigestPort;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Business plugin JAR digest 포트의 DB 구현체입니다(§15.2).
 *
 * <p>JAR 바이트는 여기서만 다루고 core 로는 hex 문자열만 올립니다 — 수 MB 짜리 아티팩트를 도메인
 * 경계 위로 올리면 manifest 를 만드는 경로가 그만큼의 힙을 잡습니다.</p>
 *
 * <p>표현은 <b>소문자 hex SHA-256</b> 이어야 합니다. Business 는 같은 바이트를
 * {@code PluginFingerprintSupport.sha256Hex} 로 계산해 manifest 값과 비교하며(§11.4), 표현이 어긋나면
 * 정상 배포가 prepare 에서 digest 불일치로 거부됩니다.</p>
 */
@Repository
public class JpaBusinessJarDigestPort implements BusinessJarDigestPort {

    private final TcJarBusinessStore jarBusinessStore;

    public JpaBusinessJarDigestPort(final TcJarBusinessStore jarBusinessStore) {
        this.jarBusinessStore = Objects.requireNonNull(jarBusinessStore, "jarBusinessStore is null");
    }

    @Override
    public Optional<JarLocator> findLocator(final long businessJarKey) {
        return jarBusinessStore.findByBusinessJarKey(businessJarKey)
                .map(jar -> new JarLocator(
                        jar.jarFileName(),
                        jar.jarFile() == null ? null : sha256Hex(jar.jarFile())));
    }

    private static String sha256Hex(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 다이제스트를 사용할 수 없습니다.", e);
        }
    }
}
