package dev.sindic.enrollmenthub.decisionengine.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The variants carry their own invariants — there are no fields to null-check — so what is left to
 * test is the completion predicate and the wire form.
 *
 * <p>The serialization tests matter more than they look: this is the repository's first polymorphic
 * type, and the {@code signals} column is written through an injected {@link JsonMapper} but read
 * back through Hibernate's format mapper. Both resolve the {@code kind} discriminator from the same
 * annotations, and {@code EnrollmentRepositoryIT} proves they agree through a real column; these
 * tests pin the shape those two have to agree on.
 */
class SignalStateTest {

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    private static Stream<SignalState> everyVariant() {
        return Stream.of(
                new SignalState.Pending(),
                new SignalState.Checked(CheckOutcome.OK),
                new SignalState.Checked(CheckOutcome.FAILED),
                new SignalState.Scored(RiskLevel.HIGH),
                new SignalState.NoResult("geocoding_failed"),
                new SignalState.NotExecuted("timeout"));
    }

    // ── completion predicate ──────────────────────────────────────────────────

    @Test
    void pending_hasNotSettled() {
        assertThat(new SignalState.Pending().hasSettled()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("everyVariant")
    void everyVariantExceptPending_hasSettled(SignalState state) {
        assertThat(state.hasSettled()).isEqualTo(!(state instanceof SignalState.Pending));
    }

    @Test
    void notExecuted_countsAsSettled() {
        // A signal that never ran is terminal: the sweep must be able to finalize the row.
        // It contributes nothing to aggregation, which is fail-open by omission, not by exclusion
        // from the completion predicate.
        assertThat(new SignalState.NotExecuted("timeout").hasSettled()).isTrue();
    }

    // ── wire form ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("everyVariant")
    void roundTripsThroughJson(SignalState original) {
        var json = mapper.writeValueAsString(original);
        assertThat(mapper.readValue(json, SignalState.class)).isEqualTo(original);
    }

    @Test
    void discriminatorIsWrittenAndNullFieldsAreNot() {
        assertThat(mapper.writeValueAsString(new SignalState.Checked(CheckOutcome.OK)))
                .isEqualTo("{\"kind\":\"CHECKED\",\"outcome\":\"OK\"}");
        assertThat(mapper.writeValueAsString(new SignalState.NotExecuted("timeout")))
                .isEqualTo("{\"kind\":\"NOT_EXECUTED\",\"reason\":\"timeout\"}");
        assertThat(mapper.writeValueAsString(new SignalState.Pending()))
                .isEqualTo("{\"kind\":\"PENDING\"}");
    }

    @Test
    void noResultAndNotExecutedAreDistinctOnTheWire() {
        // ADR-14 requires "did not respond" and "responded without a result" stay distinguishable.
        // The old model expressed both as a null result field; here the tag separates them.
        var ran    = mapper.writeValueAsString(new SignalState.NoResult("geocoding_failed"));
        var neverRan = mapper.writeValueAsString(new SignalState.NotExecuted("timeout"));

        assertThat(ran).contains("\"kind\":\"NO_RESULT\"");
        assertThat(neverRan).contains("\"kind\":\"NOT_EXECUTED\"");
        assertThat(ran).isNotEqualTo(neverRan);
    }

    @Test
    void everyVariantIsRegisteredForDeserialization() {
        // A variant added to the sealed interface but not to @JsonSubTypes fails here rather than
        // at the first row that carries it.
        List<SignalState> variants = everyVariant().toList();
        for (var v : variants) {
            var json = mapper.writeValueAsString(v);
            assertThat(mapper.readValue(json, SignalState.class))
                    .as("variant %s round-trips", v.getClass().getSimpleName())
                    .isEqualTo(v);
        }
        assertThat(SignalState.class.getPermittedSubclasses()).hasSize(5);
    }
}
