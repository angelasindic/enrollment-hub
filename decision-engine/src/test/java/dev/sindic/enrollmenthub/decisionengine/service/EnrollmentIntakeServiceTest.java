package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentEvent;
import dev.sindic.enrollmenthub.decisionengine.amqp.CheckRequestPublisher;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentIntakePublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
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
    @Mock CheckRequestPublisher checkRequestPublisher;
    @Mock EnrollmentCorrelationService correlationService;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final tools.jackson.databind.json.JsonMapper JSON_MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    private EnrollmentIntakeService buildService() {
        return new EnrollmentIntakeService(
                repository, correlationService, intakePublisher, checkRequestPublisher, JSON_MAPPER, clock, TIMEOUT);
    }

    @Nested
    class ReceiveEnrollment {

        @Test
        void publishesToIntakeAndDoesNotSave_creditCard() {
            var command = creditCardCommand();

            var response = buildService().receiveEnrollment(command);

            assertThat(response.enrollmentId()).isEqualTo(command.enrollmentId().toString());
            then(repository).should(never()).save(any());
            then(checkRequestPublisher).should(never()).dispatch(any(), any());

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
            then(checkRequestPublisher).should(never()).dispatch(any(), any());
        }
    }

    @Nested
    class ProcessEnrollment {

        @Test
        void redeliveredIntake_retiesDownstreamPublish() {
            // When saveIfAbsent returns true the row already exists (redelivery).
            // The service skips the save but still publishes — the previous delivery
            // may have committed the row and then crashed before the publish completed.
            var command = creditCardCommand();
            given(correlationService.saveIfAbsent(any(), any())).willReturn(true);

            buildService().processEnrollment(NOW, command);

            then(correlationService).should().saveIfAbsent(NOW, command);
            then(checkRequestPublisher).should().dispatch(any(), any());
            then(intakePublisher).should(never()).publish(any());
        }

        @Test
        void persistsCorrelationRow_andPublishesToEventsExchange_creditCard() {
            var command = creditCardCommand();
            // saveIfAbsent returns false (new row) by default

            buildService().processEnrollment(NOW, command);

            then(correlationService).should().saveIfAbsent(NOW, command);
            then(intakePublisher).should(never()).publish(any());
            var dataCaptor = ArgumentCaptor.forClass(EnrollmentData.class);
            then(checkRequestPublisher).should().dispatch(dataCaptor.capture(), any());
            assertThat(dataCaptor.getValue().paymentType().name()).isEqualTo("CREDIT_CARD");
        }

        @Test
        void persistsCorrelationRow_andPublishesToEventsExchange_invoice() {
            var command = invoiceCommand();

            buildService().processEnrollment(NOW, command);

            then(correlationService).should().saveIfAbsent(NOW, command);
            var dataCaptor = ArgumentCaptor.forClass(EnrollmentData.class);
            then(checkRequestPublisher).should().dispatch(dataCaptor.capture(), any());
            assertThat(dataCaptor.getValue().paymentType().name()).isEqualTo("INVOICE");
        }

        @Test
        void correlationServiceFailurePropagates_andPublisherNotInvoked() {
            doThrow(new RuntimeException("DB down")).when(correlationService).saveIfAbsent(any(), any());

            assertThatThrownBy(() -> buildService().processEnrollment(NOW, creditCardCommand()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB down");

            then(checkRequestPublisher).should(never()).dispatch(any(), any());
        }

        @Test
        void publisherFailurePropagates_afterSave() {
            // The @Transactional boundary on saveIfAbsent rolls back the save;
            // this unit test only proves the propagation. The rollback property
            // is covered by EnrollmentIntakeServiceIT.
            doThrow(new RuntimeException("broker down")).when(checkRequestPublisher).dispatch(any(), any());

            assertThatThrownBy(() -> buildService().processEnrollment(NOW, creditCardCommand()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("broker down");

            then(correlationService).should().saveIfAbsent(any(), any());
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
