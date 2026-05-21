package dev.sindic.enrollmenthub.decisionengine.domain;

public record EnrollmentCommand(PaymentType paymentType,
                                Person person,
                                Address shippingAddress,
                                Address billingAddress
) { }
