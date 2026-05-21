package dev.sindic.enrollmenthub.decisionengine.service;

import dev.sindic.enrollmenthub.contracts.events.EnrollmentAccepted;
import dev.sindic.enrollmenthub.decisionengine.amqp.EnrollmentAcceptedPublisher;
import dev.sindic.enrollmenthub.decisionengine.domain.*;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentEntity;
import dev.sindic.enrollmenthub.decisionengine.persistence.EnrollmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CreateEnrollmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-09T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMinutes(1);

    @Mock EnrollmentRepository repository;
    @Mock EnrollmentAcceptedPublisher publisher;
    @Spy  Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final tools.jackson.databind.json.JsonMapper JSON_MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();

    private CreateEnrollmentService buildService() {
        return new CreateEnrollmentService(repository, publisher, JSON_MAPPER, clock, TIMEOUT);
    }

    @Test
    void creditCardRequestPersistsEntityWithCorrectInitialisation() {
        var response = buildService().createEnrollment(creditCardCommand());

        assertThat(response.status()).isEqualTo(PendingEnrollmentResponse.Status.ACCEPTED);
        assertThat(response.requestId()).isNotNull();

        var captor = ArgumentCaptor.forClass(EnrollmentEntity.class);
        then(repository).should().save(captor.capture());

        var entity = captor.getValue();
        assertThat(entity.getRequestId()).isEqualTo(response.requestId());
        assertThat(entity.getPaymentType()).isEqualTo(PaymentType.CREDIT_CARD);
        assertThat(entity.getSignals()).containsOnlyKeys(SignalConfig.GEO_SCORE, SignalConfig.FRAUD_CHECK);
        assertThat(entity.getSignals().get(SignalConfig.GEO_SCORE).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
        assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getTimeoutAt()).isEqualTo(NOW.plus(TIMEOUT));
    }

    @Test
    void invoiceRequestPersistsEntityWithCorrectInitialisation() {
        var response = buildService().createEnrollment(invoiceCommand());

        assertThat(response.status()).isEqualTo(PendingEnrollmentResponse.Status.ACCEPTED);

        var captor = ArgumentCaptor.forClass(EnrollmentEntity.class);
        then(repository).should().save(captor.capture());

        var entity = captor.getValue();
        assertThat(entity.getPaymentType()).isEqualTo(PaymentType.INVOICE);
        assertThat(entity.getSignals()).containsOnlyKeys(SignalConfig.FRAUD_CHECK);
        assertThat(entity.getSignals().get(SignalConfig.FRAUD_CHECK).processingState())
                .isEqualTo(SignalProcessingState.PENDING);
    }

    @Test
    void publishesEnrollmentAcceptedWithPreservedRequestId() {
        var response = buildService().createEnrollment(creditCardCommand());

        var captor = ArgumentCaptor.forClass(EnrollmentAccepted.class);
        then(publisher).should().publish(captor.capture());

        var event = captor.getValue();
        assertThat(event.requestId()).isEqualTo(response.requestId());
        assertThat(event.enrollmentData().paymentType().name()).isEqualTo("CREDIT_CARD");
        assertThat(event.enrollmentData().person().emailAddress()).isEqualTo("ada@example.com");
        assertThat(event.enrollmentData().shippingAddress().city()).isEqualTo("Berlin");
    }

    @Test
    void repositoryFailurePropagatesAndPublisherNotInvoked() {
        doThrow(new RuntimeException("DB down")).when(repository).save(any());

        assertThatThrownBy(() -> buildService().createEnrollment(creditCardCommand()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");

        then(publisher).should(never()).publish(any());
    }

    @Test
    void publisherFailurePropagates() {
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        assertThatThrownBy(() -> buildService().createEnrollment(creditCardCommand()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("broker down");

        then(repository).should().save(any());
    }

    private static EnrollmentCommand creditCardCommand() {
        return new EnrollmentCommand(
                PaymentType.CREDIT_CARD,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }

    private static EnrollmentCommand invoiceCommand() {
        return new EnrollmentCommand(
                PaymentType.INVOICE,
                new Person("Ada", "Lovelace", "ada@example.com", "+49123"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"),
                new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE"));
    }
}
