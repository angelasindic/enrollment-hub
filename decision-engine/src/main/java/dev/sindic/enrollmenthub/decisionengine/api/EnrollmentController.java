package dev.sindic.enrollmenthub.decisionengine.api;

import dev.sindic.enrollmenthub.decisionengine.domain.Address;
import dev.sindic.enrollmenthub.decisionengine.domain.EnrollmentCommand;
import dev.sindic.enrollmenthub.decisionengine.domain.PaymentType;
import dev.sindic.enrollmenthub.decisionengine.domain.Person;
import dev.sindic.enrollmenthub.decisionengine.service.EnrollmentIntakeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Entry point for the enrollment flow.
 * <p>
 * Accepts {@link EnrollmentRequest}, mints a correlation {@code enrollmentId},
 * and responds immediately with {@code 202 Accepted}.
 * This controller establishes the HTTP contract.
 */
@RestController
@RequestMapping("/enrollment/public/v1/enrollments")
@Tag(name = "Enrollment",
        description = "Accepts enrollment requests and orchestrates asynchronous " +
                "assurance checks (geo-scoring, fraud detection, identity verification) " +
                "via scatter-gather. The final enrollment decision is delivered out-of-band.")
@RequiredArgsConstructor
public class EnrollmentController {

    private final EnrollmentIntakeService enrollmentService;

    @PostMapping
    @Operation(
            summary = "Submit an enrollment request",
            description = """
                    Accepts the enrollment data, persists a correlation record, and publishes \
                    an EnrollmentAccepted event to initiate the assurance pipeline. \
                    The request is routed by payment type — credit card requests trigger \
                    geo-scoring, invoice requests trigger identity verification, and both \
                    routes trigger fraud detection. \
                    Returns immediately with a correlation enrollmentId; the final \
                    EnrollmentDecisionEvent is delivered asynchronously.""",
            responses = {
                    @ApiResponse(
                            responseCode = "202",
                            description = "Request accepted for asynchronous processing",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = EnrollmentResponse.class))),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid request payload — missing or malformed fields",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ErrorResponse.class))),
                    @ApiResponse(
                            responseCode = "503",
                            description = "Messaging infrastructure unavailable — retry later",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ErrorResponse.class))),
                    @ApiResponse(
                            responseCode = "500",
                            description = "Unexpected server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ErrorResponse.class)))
            })
    public ResponseEntity<EnrollmentResponse> createEnrollment(@Valid @RequestBody EnrollmentRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new EnrollmentResponse(
                        enrollmentService.receiveEnrollment(createDomainRequest(request)).enrollmentId())
                );
    }

    EnrollmentCommand createDomainRequest(EnrollmentRequest request) {
        return new EnrollmentCommand(
                UUID.randomUUID(),
                PaymentType.valueOf(request.paymentType().name()),
                toPerson(request.person()),
                toAddress(request.shippingAddress()),
                toAddress(request.billingAddress())
        );
    }

    private static Person toPerson(EnrollmentRequest.PersonDto dto) {
        return new Person(dto.firstName(), dto.lastName(), dto.emailAddress(), dto.phoneNumber());
    }

    private static Address toAddress(EnrollmentRequest.AddressDto dto) {
        return new Address(dto.streetLines(), dto.postalCode(), dto.city(), dto.subregion(), dto.countryCode());
    }
}
