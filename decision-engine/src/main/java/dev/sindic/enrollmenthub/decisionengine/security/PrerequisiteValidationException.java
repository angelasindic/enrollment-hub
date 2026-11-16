package dev.sindic.enrollmenthub.decisionengine.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Raised when a required prerequisite token is missing or fails validation. Rendered as {@code 403}
 * (ADR-03: the request is rejected before any correlation record is created).
 */
public class PrerequisiteValidationException extends ResponseStatusException {

    public PrerequisiteValidationException(String reason) {
        this(reason, null);
    }

    public PrerequisiteValidationException(String reason, Throwable cause) {
        super(HttpStatus.FORBIDDEN, reason, cause);
    }
}
