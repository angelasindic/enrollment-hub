package dev.sindic.enrollmenthub.decisionengine.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreateEnrollmentProcessTest {

    private static final Instant NOW = Instant.parse("2026-04-09T12:00:00Z");
    private static final Instant TIMEOUT = NOW.plusSeconds(60);

    private static EnrollmentCommand creditCardCommand() {
        return new EnrollmentCommand(
                UUID.randomUUID(),
                PaymentType.CREDIT_CARD,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }

    private static EnrollmentCommand invoiceCommand() {
        return new EnrollmentCommand(
                UUID.randomUUID(),
                PaymentType.INVOICE,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }

    @Test
    void startCreditCard_containsOnlyApplicableSignals() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT);

        assertThat(process.signals()).containsOnlyKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
        assertThat(process.signals().get(SignalConfig.GEO_SCORE).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
        assertThat(process.signals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
    }

    @Test
    void startInvoice_containsOnlyFraudCheck() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), invoiceCommand(), NOW, TIMEOUT);

        assertThat(process.signals()).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
        assertThat(process.signals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
    }

    @Test
    void isCompleteReturnsFalseWhenSignalsPending() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT);
        assertThat(process.isComplete()).isFalse();
    }

    @Test
    void creditCardCompletesWhenBothSignalsSettle() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                .withSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.LOW))
                .withSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

        assertThat(process.isComplete()).isTrue();
        assertThat(process.signals().get(SignalConfig.GEO_SCORE).riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(process.signals().get(SignalConfig.FRAUD_CHECK).outcome()).isEqualTo(SignalOutcome.OK);
    }

    @Test
    void invoiceCompletesWhenFraudSettles() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), invoiceCommand(), NOW, TIMEOUT)
                .withSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

        assertThat(process.isComplete()).isTrue();
    }

    @Test
    void creditCardNotCompleteWhenOnlyGeoSettles() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                .withSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.MEDIUM));

        assertThat(process.isComplete()).isFalse();
    }

    @Test
    void withTimeout_transitionsPendingToFailed() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                .withTimeout();

        assertThat(process.signals().get(SignalConfig.GEO_SCORE).processingState())
                .isEqualTo(SignalProcessingState.FAILED);
        assertThat(process.signals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.FAILED);
        assertThat(process.isComplete()).isTrue();
    }

    @Test
    void withTimeout_preservesAlreadySettledSignal() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                .withSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.HIGH))
                .withTimeout();

        assertThat(process.signals().get(SignalConfig.GEO_SCORE).processingState())
                .isEqualTo(SignalProcessingState.SETTLED);
        assertThat(process.signals().get(SignalConfig.GEO_SCORE).riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(process.signals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.FAILED);
    }

    @Test
    void invoiceTimeout_onlyAffectsFraud() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), invoiceCommand(), NOW, TIMEOUT)
                .withTimeout();

        assertThat(process.signals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.FAILED);
        assertThat(process.isComplete()).isTrue();
    }

    @Test
    void extremeRiskLevel_isAValidSettledResult() {
        var process = EnrollmentProcess.start(UUID.randomUUID(), creditCardCommand(), NOW, TIMEOUT)
                .withSignalResult(SignalConfig.GEO_SCORE, SignalState.settled(RiskLevel.EXTREME))
                .withSignalResult(SignalConfig.FRAUD_CHECK, SignalState.settled(SignalOutcome.OK));

        assertThat(process.isComplete()).isTrue();
        assertThat(process.signals().get(SignalConfig.GEO_SCORE).riskLevel()).isEqualTo(RiskLevel.EXTREME);
    }
}
