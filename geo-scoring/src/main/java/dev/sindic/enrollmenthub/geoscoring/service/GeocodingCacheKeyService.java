package dev.sindic.enrollmenthub.geoscoring.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces a deterministic geocoding cache key from a normalized address string using
 * HMAC-SHA256 with a secret pepper. The pepper prevents precomputation attacks —
 * an attacker who intercepts cache keys cannot reverse or brute-force the original address
 * without knowing the secret.
 *
 * <p>Key format: lowercase hex digest (64 characters).
 * Configure the secret via the {@code GEOCODING_CACHE_HMAC_SECRET} environment variable.
 */
@Component
public class GeocodingCacheKeyService {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec keySpec;

    public GeocodingCacheKeyService(@Value("${geocoding.cache.hmac-secret}") String hmacSecret) {
        this.keySpec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /**
     * Returns the HMAC-SHA256 hex digest of the given normalized address, keyed with the
     * configured secret.
     *
     * @param normalizedAddress canonical address string produced by {@link AddressNormalizationService}
     * @return 64-character lowercase hex string
     */
    public String keyFor(String normalizedAddress) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);
            var hash = mac.doFinal(normalizedAddress.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is mandated by the Java SE specification — unreachable
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
