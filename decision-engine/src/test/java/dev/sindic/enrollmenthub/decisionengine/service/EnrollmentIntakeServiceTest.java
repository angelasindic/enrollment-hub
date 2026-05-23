package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentAcceptedPublisher;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentIntakePublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

/**
 * Unit tests for the two EnrollmentIntakeService paths:
 *
 * <ul>
 *   <li>{@code receiveEnrollment} — REST entry; publishes to the intake queue
 *       (Layer 1, ADR-003). No DB writes.</li>
 *   <li>{@code processEnrollment} — listener handler; @Transactional, persists
 *       the correlation row and publishes to the events exchange (Layer 2).</li>
 * </ul>
 *
 * Each path mocks the publisher it should call and verifies the other path's
 * publisher is never invoked.
 */
@ExtendWith(MockitoExtension.class)
class EnrollmentIntakeServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-23T10:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    @Mock EnrollmentRepository repository;
    @Mock EnrollmentIntakePublisher intakePublisher;
    @Mock EnrollmentAcceptedPublisher acceptedPublisher;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final tools.jackson.databind.json.JsonMapper JSON_MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    private EnrollmentIntakeService buildService() {
        return new EnrollmentIntakeService(
                repository, intakePublisher, acceptedPublisher, JSON_MAPPER, clock, TIMEOUT);
    }

    @Nested
    class ReceiveEnrollment {

        @Test
        void publishesToIntakeAndDoesNotSave_creditCard() {
            var command = creditCardCommand();

            var response = buildService().receiveEnrollment(command);

            assertThat(response.enrollmentId()).isEqualTo(command.enrollmentId().toString());
            then(repository).should(never()).save(any());
            then(acceptedPublisher).should(never()).publish(any());

            var captor = ArgumentCaptor.forClass(EnrollmentEvent.class);
            then(intakePublisher).should().publish(captor.capture());
            var event = captor.getValue();
            assertThat(event.createdAt()).isEqualTo(NOW);
            assertThat(event.enrollmentData().enrollmentId()).isEqualTo(command.enrollmentId());
            assertThat(event.enrollmentData().paymentType().name()).isEqualTo("CREDIT_CARD");
            assertThat(event.enrollmentData().person().emailAddress()).isEqualTo("ada@example.com");
            assertThat(event.enrollmentData().shippingAddress().city()).isEqualTo("Berlin");
        }

        @Test
        void publishesToIntake_invoice() {
            var command = invoiceCommand();

            buildService().receiveEnrollment(command);

            var captor = ArgumentCaptor.forClass(EnrollmentEvent.class);
            then(intakePublisher).should().publish(captor.capture());
            assertThat(captor.getValue().enrollmentData().paymentType().name()).isEqualTo("INVOICE");
        }

        @Test
        void publisherFailurePropagates_andNothingPersisted() {
            doThrow(new RuntimeException("broker down")).when(intakePublisher).publish(any());

            assertThatThrownBy(() -> buildService().receiveEnrollment(creditCardCommand()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("broker down");

            then(repository).should(never()).save(any());
            then(acceptedPublisher).should(never()).publish(any());
        }
    }

    @Nested
    class ProcessEnrollment {

        @Test
        void idempotentDiscardWhenRowAlreadyExists() {
            // Simulate a redelivered intake message — the row already exists
            // from the original delivery. Listener should return cleanly so
            // AMQP can ACK; no duplicate save, no duplicate publish.
            var command = creditCardCommand();
            given(repository.existsById(command.enrollmentId())).willReturn(true);

            buildService().processEnrollment(NOW, command);

            then(repository).should(never()).save(any());
            then(acceptedPublisher).should(never()).publish(any());
            then(intakePublisher).should(never()).publish(any());
        }

        @Test
        void persistsCorrelationRow_andPublishesToEventsExchange_creditCard() {
            var command = creditCardCommand();

            buildService().processEnrollment(NOW, command);

            var entityCaptor = ArgumentCaptor.forClass(EnrollmentEntity.class);
            then(repository).should().save(entityCaptor.capture());
            var entity = entityCaptor.getValue();
            assertThat(entity.getRequestId()).isEqualTo(command.enrollmentId());
            assertThat(entity.getPaymentType()).isEqualTo(PaymentType.CREDIT_CARD);
            assertThat(entity.getSignals()).containsOnlyKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
            assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                    .isEqualTo(SignalProcessingState.PENDING);
            assertThat(entity.getCreatedAt()).isEqualTo(NOW);
            assertThat(entity.getTimeoutAt()).isEqualTo(NOW.plus(TIMEOUT));

            then(intakePublisher).should(never()).publish(any());
            var eventCaptor = ArgumentCaptor.forClass(EnrollmentEvent.class);
            then(acceptedPublisher).should().publish(eventCaptor.capture());
            assertThat(eventCaptor.getValue().enrollmentData().paymentType().name()).isEqualTo("CREDIT_CARD");
        }

        @Test
        void persistsCorrelationRow_andPublishesToEventsExchange_invoice() {
            var command = invoiceCommand();

            buildService().processEnrollment(NOW, command);

            var entityCaptor = ArgumentCaptor.forClass(EnrollmentEntity.class);
            then(repository).should().save(entityCaptor.capture());
            var entity = entityCaptor.getValue();
            assertThat(entity.getPaymentType()).isEqualTo(PaymentType.INVOICE);
            assertThat(entity.getSignals()).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
        }

        @Test
        void repositoryFailurePropagates_andPublisherNotInvoked() {
            doThrow(new RuntimeException("DB down")).when(repository).save(any());

            assertThatThrownBy(() -> buildService().processEnrollment(NOW, creditCardCommand()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB down");

            then(acceptedPublisher).should(never()).publish(any());
        }

        @Test
        void publisherFailurePropagates_afterSave() {
            // The @Transactional boundary would roll back the save; this unit
            // test only proves the propagation. The rollback property is
            // covered by EnrollmentIntakeServiceIT.
            doThrow(new RuntimeException("broker down")).when(acceptedPublisher).publish(any());

            assertThatThrownBy(() -> buildService().processEnrollment(NOW, creditCardCommand()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("broker down");

            then(repository).should().save(any());
        }
    }

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
}
