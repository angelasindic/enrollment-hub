package dev.sindic.enrollmenthub.accountservice.service;

import dev.sindic.enrollmenthub.contracts.domain.Address;
import dev.sindic.enrollmenthub.contracts.domain.EnrollmentSnapshot;
import dev.sindic.enrollmenthub.contracts.domain.PaymentType;
import dev.sindic.enrollmenthub.contracts.domain.Person;
import dev.sindic.enrollmenthub.contracts.events.DecisionResult;
import dev.sindic.enrollmenthub.contracts.events.EnrollmentDecisionEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountProvisioningServiceTest {

    @Test
    void onDecision_handlesFirstDeliveryAndDropsRedelivery() {
        var service = new AccountProvisioningService(new AccountServiceProperties(10));
        var event = decision(UUID.randomUUID());

        assertThat(service.onDecision(event)).isTrue();
        assertThat(service.onDecision(event)).isFalse();
    }

    @Test
    void onDecision_handlesDistinctDecisionsIndependently() {
        var service = new AccountProvisioningService(new AccountServiceProperties(10));

        assertThat(service.onDecision(decision(UUID.randomUUID()))).isTrue();
        assertThat(service.onDecision(decision(UUID.randomUUID()))).isTrue();
    }

    @Test
    void onDecision_forgetsIdsBeyondCapacity() {
        var service = new AccountProvisioningService(new AccountServiceProperties(2));
        var oldest = decision(UUID.randomUUID());

        service.onDecision(oldest);
        service.onDecision(decision(UUID.randomUUID()));
        service.onDecision(decision(UUID.randomUUID()));

        assertThat(service.onDecision(oldest)).isTrue();
    }

    private static EnrollmentDecisionEvent decision(UUID decisionId) {
        var person   = new Person("Ada", "Lovelace", "ada@example.com", "+49123");
        var address  = new Address(List.of("1 Main St"), "10115", "Berlin", "BE", "DE");
        var snapshot = new EnrollmentSnapshot(PaymentType.INVOICE, person, address, address);
        return new EnrollmentDecisionEvent(
                decisionId, snapshot, DecisionResult.APPROVED, Map.of(), Instant.parse("2026-05-21T10:00:00Z"));
    }
}
