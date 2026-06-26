package dev.sindic.enrollmenthub.contracts.domain;

/** Routing discriminator — determines which primary check activates (see §5.2). */
public enum PaymentType {
    CREDIT_CARD,
    INVOICE
}
