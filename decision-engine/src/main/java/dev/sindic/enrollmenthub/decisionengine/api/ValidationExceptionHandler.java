package dev.sindic.enrollmenthub.decisionengine.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Maps infrastructure and validation exceptions to RFC 9457 Problem Details responses.
 */
@Slf4j
@ControllerAdvice
public class ValidationExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AmqpException.class)
    ResponseEntity<ProblemDetail> handleAmqpException(AmqpException ex) {
        log.error("AMQP publish failure", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Messaging service unavailable");
        problem.setDetail("The request could not be processed at this time. Please retry later.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem = ex.getBody();

        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"))
                .toList();

        problem.setDetail("Validation failed for " + fieldErrors.size() + " field(s).");
        problem.setProperty("fieldErrors", fieldErrors);

        return ResponseEntity.status(status).headers(headers).body(problem);
    }
}
