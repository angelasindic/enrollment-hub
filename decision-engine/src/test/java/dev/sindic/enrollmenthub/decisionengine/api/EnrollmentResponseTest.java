package dev.sindic.enrollmenthub.decisionengine.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EnrollmentResponseTest {

    @Test
    void acceptedFactorySetsStatusAccepted() {
        UUID id = UUID.randomUUID();
        EnrollmentResponse response = new EnrollmentResponse(id, EnrollmentResponse.Status.ACCEPTED);

        assertThat(response.requestId()).isEqualTo(id);
        assertThat(response.status()).isEqualTo(EnrollmentResponse.Status.ACCEPTED);
    }

    @Test
    void rejectsNullFields() {
        assertThatNullPointerException().isThrownBy(() ->
                new EnrollmentResponse(null, EnrollmentResponse.Status.ACCEPTED));
        assertThatNullPointerException().isThrownBy(() ->
                new EnrollmentResponse(UUID.randomUUID(), null));
    }
}
