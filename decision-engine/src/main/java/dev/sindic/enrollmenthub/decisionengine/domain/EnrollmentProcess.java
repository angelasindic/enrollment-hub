package dev.sindic.enrollmenthub.decisionengine.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Correlation record for an in-flight enrollment request.
 *
 * <p>Tracks the lifecycle of every applicable signal in the scatter-gather pipeline
 * via a {@code Map<SignalConfig, SignalState>}. Only signals applicable to the
 * payment route are present; absence from the map means the signal is not applicable.
 *
 * <p>The record is immutable — state transitions return a new instance. The completion
 * predicate ({@link #isComplete()}) evaluates to {@code true} when every signal in
 * the map has reached a terminal state.
 *
 * @see SignalConfig
 * @see SignalState
 */
public record EnrollmentProcess(
        UUID requestId,
        EnrollmentCommand command,
        Map<SignalConfig, SignalState> signals,
        Instant createdAt,
        Instant timeoutAt
) {

    public EnrollmentProcess {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(signals, "signals must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(timeoutAt, "timeoutAt must not be null");
        signals = Collections.unmodifiableMap(new EnumMap<>(signals));
    }

    /**
     * Creates a new process with signals initialised for the given payment type.
     * Derived entirely from {@link SignalConfig} metadata.
     */
    public static EnrollmentProcess start(UUID requestId,
                                          EnrollmentCommand command,
                                          Instant createdAt,
                                          Instant timeoutAt) {
        return new EnrollmentProcess(requestId, command,
                SignalConfig.initializeFor(command.paymentType()),
                createdAt, timeoutAt);
    }

    /** Returns {@code true} when all applicable signals have settled. */
    public boolean isComplete() {
        return SignalConfig.allSettled(signals);
    }

    /** Returns a new process with the given signal state recorded. */
    public EnrollmentProcess withSignalResult(SignalConfig signal, SignalState state) {
        var updated = new EnumMap<>(signals);
        updated.put(signal, state);
        return new EnrollmentProcess(requestId, command, updated, createdAt, timeoutAt);
    }

    /**
     * Transitions all {@link SignalProcessingState#PENDING} signals to
     * {@link SignalProcessingState#FAILED}. Already terminal signals are unchanged.
     */
    public EnrollmentProcess withTimeout() {
        var updated = new EnumMap<>(signals);
        updated.replaceAll((signal, state) ->
                state.processingState() == SignalProcessingState.PENDING
                        ? SignalState.failed()
                        : state);
        return new EnrollmentProcess(requestId, command, updated, createdAt, timeoutAt);
    }
}
