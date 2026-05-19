package dev.sindic.enrollmenthub.geoscoring.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.geoscoring.libpostal.AddressComponent;
import dev.sindic.enrollmenthub.geoscoring.libpostal.LibpostalClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressNormalizationServiceTest {

    @Mock
    LibpostalClient libpostalClient;

    @InjectMocks
    AddressNormalizationService service;

    @Test
    void normalize_joinsSortedComponentValues() {
        var address = new Address(List.of("123 Main St"), "10115", "Berlin", "BE", "DE");
        when(libpostalClient.parse("123 Main St, 10115, Berlin, BE, DE")).thenReturn(List.of(
                new AddressComponent("road", "main street"),
                new AddressComponent("city", "berlin"),
                new AddressComponent("house_number", "123")
        ));

        var result = service.normalize(address);

        assertThat(result.canonical()).isEqualTo("city:berlin house_number:123 road:main street");
        assertThat(result.components()).containsExactlyInAnyOrder(
                new AddressComponent("road", "main street"),
                new AddressComponent("city", "berlin"),
                new AddressComponent("house_number", "123")
        );
    }

    @Test
    void normalize_sortByLabelIsStableAcrossResponseOrder() {
        var address = new Address(List.of("Teststrasse 1"), "10115", "Berlin", "BE", "DE");
        when(libpostalClient.parse("Teststrasse 1, 10115, Berlin, BE, DE")).thenReturn(List.of(
                new AddressComponent("road", "teststrasse"),
                new AddressComponent("city", "berlin"),
                new AddressComponent("house_number", "1")
        ));

        assertThat(service.normalize(address).canonical()).isEqualTo("city:berlin house_number:1 road:teststrasse");
    }

    @Test
    void normalize_emptyComponents_returnsPreProcessedString() {
        var address = new Address(List.of("Unknown St"), "00000", "Nowhere", null, "XX");
        when(libpostalClient.parse("Unknown St, 00000, Nowhere, XX")).thenReturn(List.of());

        var result = service.normalize(address);

        assertThat(result.canonical()).isEqualTo("address:Unknown St, 00000, Nowhere, XX");
        assertThat(result.components()).containsExactly(new AddressComponent("address", "Unknown St, 00000, Nowhere, XX"));
    }

    @Test
    void normalize_clientThrows_returnsPreProcessedString() {
        var address = new Address(List.of("Some St"), "12345", "City", "ST", "DE");
        when(libpostalClient.parse("Some St, 12345, City, ST, DE")).thenThrow(new RuntimeException("libpostal down"));

        var result = service.normalize(address);

        assertThat(result.canonical()).isEqualTo("address:Some St, 12345, City, ST, DE");
        assertThat(result.components()).containsExactly(new AddressComponent("address", "Some St, 12345, City, ST, DE"));
    }
}
