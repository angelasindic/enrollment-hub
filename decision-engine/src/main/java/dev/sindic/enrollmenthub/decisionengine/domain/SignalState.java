package dev.sindic.enrollmenthub.decisionengine.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Objects;

/**
 * The settled or in-flight state of one signal (ADR-14). Five variants, each carrying only the data
 * it has, so an invalid combination cannot be constructed.
 *
 * <p><b>This type is also the persisted format.</b> There is no separate persistence
 * representation: the {@code signals} JSONB column stores these records directly, keyed by
 * {@link SignalConfig}. That keeps the storage layer free of a translation, and the price is that
 * the column inherits this type's polymorphism — every value carries a {@code kind} discriminator,
 * and any code serialising the map by hand must supply the declared value type or Jackson silently
 * omits the tag and writes a row that cannot be read back. ADR-16 §Write path makes every write an
 * explicit {@code UPDATE} with the JSON produced in application code rather than by Hibernate, so
 * that hand-serialisation is the normal path, not an edge case. {@code SignalMapJson} is the single
 * place it happens.
 *
 * <p>The alternative — a flat persistence record mapped to and from these variants — would keep
 * Jackson's type machinery out of the column entirely, at the cost of another translation on a path
 * that already carries several. Worth reconsidering if a second polymorphic type reaches storage;
 * not worth it for one.
 */
// Discriminator lives in the serialization layer; the JSON stays flat.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SignalState.Pending.class,     name = "PENDING"),
        @JsonSubTypes.Type(value = SignalState.Checked.class,     name = "CHECKED"),
        @JsonSubTypes.Type(value = SignalState.Scored.class,      name = "SCORED"),
        @JsonSubTypes.Type(value = SignalState.NoResult.class,    name = "NO_RESULT"),
        @JsonSubTypes.Type(value = SignalState.NotExecuted.class, name = "NOT_EXECUTED"),
})
public sealed interface SignalState {

    default boolean hasSettled() { return !(this instanceof Pending); }

    /** Dispatched, no reply yet. */
    record Pending() implements SignalState {}

    /** Check-style signal produced a verdict (BEST_EFFORT / REQUIRED). */
    record Checked(CheckOutcome outcome) implements SignalState {
        public Checked {
            Objects.requireNonNull(outcome, "a checked signal records its verdict");
        }
    }

    /** Score-style signal produced a risk tier (SCORING_SIGNAL). */
    record Scored(RiskLevel riskLevel) implements SignalState {
        public Scored {
            Objects.requireNonNull(riskLevel, "a scored signal records its risk tier");
        }
    }

    /** Ran, could not produce a result. Fail-open. */
    record NoResult(String reason) implements SignalState {
        public NoResult {
            Objects.requireNonNull(reason, "a no-result records why there is no result");
        }
    }

    /** Never ran — timeout or crash. Fail-open. */
    record NotExecuted(String reason) implements SignalState {
        public NotExecuted {
            Objects.requireNonNull(reason, "a signal that never ran records why it is missing");
        }
    }
}

