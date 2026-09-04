package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Serialises the {@code signals} map for the JSONB column.
 *
 * <p>{@link SignalState} carries a {@code kind} discriminator from {@code @JsonTypeInfo} on the
 * sealed interface. Handed a bare {@code Map}, whose value type is erased, Jackson picks each
 * value's serialiser from its runtime class and omits the tag:
 *
 * <pre>
 * writeValueAsString(map)          → {"GEO_SCORE":{"riskLevel":"HIGH"}}
 * writerFor(SIGNAL_MAP).write(map) → {"GEO_SCORE":{"kind":"SCORED","riskLevel":"HIGH"}}
 * </pre>
 *
 * <p>Hibernate reads the column against the declared {@code Map<SignalConfig, SignalState>} and
 * rejects a value with no {@code kind}, so the first form writes rows that cannot be read back. The
 * tag survives at the root of a document, so the bug appears only inside a collection. Every write
 * goes through here, tests included.
 *
 * <p>No {@code read} counterpart: production deserialises this column only through Hibernate, which
 * takes the type from {@code EnrollmentEntity.signals}. An untyped read fails worse — it yields
 * {@code LinkedHashMap} values and throws nothing, so every {@code instanceof} pattern silently
 * fails to match. Give a production read the type token if one is ever added.
 *
 * @see SignalState why the domain type is the persisted format, and what that costs
 */
public final class SignalMapJson {

    private static final TypeReference<Map<SignalConfig, SignalState>> SIGNAL_MAP =
            new TypeReference<>() {};

    private SignalMapJson() {}

    public static String write(JsonMapper mapper, Map<SignalConfig, SignalState> signals) {
        return mapper.writerFor(SIGNAL_MAP).writeValueAsString(signals);
    }
}
