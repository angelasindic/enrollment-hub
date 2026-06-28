package dev.sindic.enrollmenthub.decisionengine.service;

import java.util.UUID;

public class EnrollmentSerializationException extends RuntimeException {

    public EnrollmentSerializationException(UUID enrollmentId, Throwable cause) {
        super("Failed to serialize enrollment data for enrollmentId=" + enrollmentId, cause);
    }
}