package dev.sindic.enrollmenthub.decisionengine.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EnrollmentResponseTest {

    @Test
    void carriesEnrollmentId() {
        String id = UUID.randomUUID().toString();

        EnrollmentResponse response = new EnrollmentResponse(id);

        assertThat(response.enrollmentId()).isEqualTo(id);
    }

    @Test
    void rejectsNullEnrollmentId() {
        assertThatNullPointerException().isThrownBy(() -> new EnrollmentResponse(null));
    }
}
