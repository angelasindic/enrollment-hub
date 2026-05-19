package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.geoscoring.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AddressNormalizationServiceIT extends BaseIntegrationTest {

    @Autowired
    AddressNormalizationService service;

    @Test
    void normalize_realLibpostal_returnsNonEmptyCanonicalForm() {
        var address = new Address(List.of("Laan van Meerdervoort 900"), "2564 AT", "Den Haag", "ZH", "NL");

        var result = service.normalize(address);

        assertThat(result.canonical()).isNotBlank();
        assertThat(result.components()).isNotEmpty();
        // libpostal normalized — canonical must differ from the raw comma-joined fallback
        assertThat(result.canonical()).isNotEqualTo("Laan van Meerdervoort 900, 2564 AT, Den Haag, ZH, NL");
    }

    @Test
    void normalize_idempotent() {
        var address = new Address(List.of("123 Main Street"), "10115", "Berlin", "BE", "DE");

        assertThat(service.normalize(address).canonical()).isEqualTo(service.normalize(address).canonical());
    }
}
