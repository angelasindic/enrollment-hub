package dev.sindic.enrollmenthub.frauddetection.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentData;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.FraudCheckResult;
import dev.sindic.enrollmenthub.contracts.events.SignalOutcome;
import dev.sindic.enrollmenthub.frauddetection.amqp.FraudCheckResultPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

class FraudDetectionServiceTest {

    private final FraudCheckResultPublisher publisher = mock(FraudCheckResultPublisher.class);
    private final FraudDetectionService service = new FraudDetectionService(publisher);

    @Test
    void check_publishesOkResultForTheEnrollment() {
        var data = enrollmentData();

        service.check(data);

        var captor = ArgumentCaptor.forClass(FraudCheckResult.class);
        then(publisher).should().publish(captor.capture());
        assertThat(captor.getValue().enrollmentId()).isEqualTo(data.enrollmentId());
        assertThat(captor.getValue().outcome()).isEqualTo(SignalOutcome.OK);
    }

    private static EnrollmentData enrollmentData() {
        var person  = new Person("Ada", "Lovelace", "ada@example.com", "+49123");
        var address = new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE");
        return new EnrollmentData(UUID.randomUUID(), PaymentType.INVOICE, person, address, address);
    }
}
