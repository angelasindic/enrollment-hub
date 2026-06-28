package dev.sindic.enrollmenthub.geoscoring.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeocodingCacheKeyServiceTest {

    private static final String TEST_SECRET = "test-secret";

    private final GeocodingCacheKeyService service =
            new GeocodingCacheKeyService(TEST_SECRET);

    @Test
    void keyFor_returnsLowercaseHexOf64Chars() {
        var key = service.keyFor("berlin 123 main street");

        assertThat(key).hasSize(64);
        assertThat(key).matches("[0-9a-f]+");
    }

    @Test
    void keyFor_isDeterministic() {
        var input = "berlin 123 main street";

        assertThat(service.keyFor(input)).isEqualTo(service.keyFor(input));
    }

    @Test
    void keyFor_differentAddresses_differentKeys() {
        assertThat(service.keyFor("berlin 123 main street"))
                .isNotEqualTo(service.keyFor("hamburg 45 reeperbahn"));
    }

    @Test
    void keyFor_differentSecrets_differentKeys() {
        var other = new GeocodingCacheKeyService("other-secret");

        assertThat(service.keyFor("berlin 123 main street"))
                .isNotEqualTo(other.keyFor("berlin 123 main street"));
    }

    @Test
    void keyFor_knownVector() {
        // HMAC-SHA256("test-secret", "berlin 123 main street")
        // verified: echo -n "berlin 123 main street" | openssl dgst -sha256 -hmac "test-secret"
        assertThat(service.keyFor("berlin 123 main street"))
                .isEqualTo("d722936f9a7d8297d44d892b155425cc5cc4ab20cda65aff908a4e8410630528");
    }
}
