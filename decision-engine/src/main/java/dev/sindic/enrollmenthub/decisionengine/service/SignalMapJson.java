package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.decisionengine.domain.SignalConfig;
import dev.sindic.enrollmenthub.decisionengine.domain.SignalState;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Serialises the {@code signals} map for the JSONB column.
 *
 * <p>The explicit type token is load-bearing, not decoration. {@link SignalState} is polymorphic and
 * carries its {@code kind} discriminator through {@code @JsonTypeInfo} on the sealed interface.
 * Handed a bare {@code Map} — whose value type is erased — Jackson resolves each value's serialiser
 * from its runtime class and writes it <em>without</em> the discriminator:
 *
 * <pre>
 * writeValueAsString(map)          → {"GEO_SCORE":{"riskLevel":"HIGH"}}            // unreadable
 * writerFor(SIGNAL_MAP).write(map) → {"GEO_SCORE":{"kind":"SCORED","riskLevel":"HIGH"}}
 * </pre>
 *
 * <p>The first form round-trips fine at the root ({@code writeValueAsString(state)} keeps the tag)
 * and fails only inside a collection, so it is easy to miss. It is also not a cosmetic difference:
 * Hibernate reads the column back against the field's declared {@code Map<SignalConfig, SignalState>}
 * and rejects a value with no {@code kind}, so an untyped write produces a row that cannot be read.
 * Every write to the column goes through here — production and tests alike — so the two
 * mappers cannot disagree.
 *
 * <p><b>There is no {@code read} counterpart</b>, and the omission is deliberate: production never
 * deserialises this column through Jackson. Hibernate does, taking the value type from
 * {@code EnrollmentEntity.signals}'s declaration, which erasure does not reach. Note that the two
 * failure modes are not equivalent — an untyped write drops the discriminator and the next read
 * throws {@code InvalidTypeIdException}, whereas an untyped read
 * ({@code readValue(json, Map.class)}) throws nothing and yields {@code LinkedHashMap} values, so
 * every {@code instanceof} pattern silently fails to match and aggregation fails every signal open.
 * If a production read is ever added, give it the type token.
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
