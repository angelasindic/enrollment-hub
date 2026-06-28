package dev.sindic.enrollmenthub.decisionengine.domain;

import java.util.UUID;

public record EnrollmentCommand(UUID enrollmentId,
                                PaymentType paymentType,
                                Person person,
                                Address shippingAddress,
                                Address billingAddress
) { }
