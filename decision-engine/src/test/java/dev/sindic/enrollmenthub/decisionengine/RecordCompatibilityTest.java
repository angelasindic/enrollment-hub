package dev.sindic.enrollmenthub.decisionengine;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.decisionengine.api.EnrollmentRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against silent drift between the three structural copies of the intake payload's value
 * objects: the API DTOs, the decision-engine domain records, and their contracts counterparts.
 *
 * <p>Adding or removing a component already breaks the build, because every mapper constructs
 * these records positionally. What nothing else catches is a <em>reordering</em> of same-typed
 * components — {@code Address} carries four consecutive {@code String}s and {@code Person} four.
 * Both crossings are one-way: {@code EnrollmentController} maps DTO → domain and
 * {@code EnrollmentMapper} maps domain → contracts, with no return leg that could disagree. A
 * transposition therefore compiles, passes, and puts the values on the wire swapped.
 *
 * <p>Comparing the ordered component signature is what makes that visible.
 *
 * @see EnumCompatibilityTest the same guard for the enums that cross these boundaries
 */
class RecordCompatibilityTest {

    @Test
    void domainAddressMatchesContracts() {
        assertThat(componentSignature(dev.sindic.enrollmenthub.decisionengine.domain.Address.class))
                .isEqualTo(componentSignature(Address.class));
    }

    @Test
    void apiAddressDtoMatchesContracts() {
        assertThat(componentSignature(EnrollmentRequest.AddressDto.class))
                .isEqualTo(componentSignature(Address.class));
    }

    @Test
    void domainPersonMatchesContracts() {
        assertThat(componentSignature(dev.sindic.enrollmenthub.decisionengine.domain.Person.class))
                .isEqualTo(componentSignature(Person.class));
    }

    @Test
    void apiPersonDtoMatchesContracts() {
        assertThat(componentSignature(EnrollmentRequest.PersonDto.class))
                .isEqualTo(componentSignature(Person.class));
    }

    /** Component names and generic types, in declaration order — so a reordering fails. */
    private static List<String> componentSignature(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName() + ": " + component.getGenericType().getTypeName())
                .toList();
    }
}
